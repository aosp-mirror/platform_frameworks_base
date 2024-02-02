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

import static android.app.admin.DevicePolicyResources.Drawables.Style.SOLID_COLORED;
import static android.app.admin.DevicePolicyResources.Strings.Core.WORK_PROFILE_BADGED_LABEL;
import static android.app.admin.DevicePolicyResources.UNDEFINED;

import android.Manifest;
import android.accounts.AccountManager;
import android.annotation.ColorInt;
import android.annotation.DrawableRes;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.StringDef;
import android.annotation.SuppressAutoDoc;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UserHandleAware;
import android.annotation.UserIdInt;
import android.annotation.WorkerThread;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.PropertyInvalidatedCache;
import android.app.admin.DevicePolicyManager;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.UserInfo;
import android.content.pm.UserInfo.UserInfoFlag;
import android.content.pm.UserProperties;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.location.LocationManager;
import android.nfc.Flags;
import android.provider.Settings;
import android.util.AndroidException;
import android.util.ArraySet;
import android.util.Log;
import android.view.WindowManager.LayoutParams;

import com.android.internal.R;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Manages users and user details on a multi-user system. There are two major categories of
 * users: fully customizable users with their own login, and profiles that share a workspace
 * with a related user.
 * <p>
 * Users are different from accounts, which are managed by
 * {@link AccountManager}. Each user can have their own set of accounts.
 * <p>
 * See {@link DevicePolicyManager#ACTION_PROVISION_MANAGED_PROFILE} for more on managed profiles.
 */
@SystemService(Context.USER_SERVICE)
@android.ravenwood.annotation.RavenwoodKeepPartialClass
public class UserManager {

    private static final String TAG = "UserManager";

    @UnsupportedAppUsage
    private final IUserManager mService;
    /** Holding the Application context (not constructor param context). */
    private final Context mContext;

    /** The userId of the constructor param context. To be used instead of mContext.getUserId(). */
    private final @UserIdInt int mUserId;

    /** The userType of UserHandle.myUserId(); empty string if not a profile; null until cached. */
    private String mProfileTypeOfProcessUser = null;

    /** Whether the device is in headless system user mode; null until cached. */
    private static Boolean sIsHeadlessSystemUser = null;

    /** Maximum length of username.
     * @hide
     */
    public static final int MAX_USER_NAME_LENGTH = 100;

    /** Maximum length of user property String value.
     * @hide
     */
    public static final int MAX_ACCOUNT_STRING_LENGTH = 500;

    /** Maximum length of account options String values.
     * @hide
     */
    public static final int MAX_ACCOUNT_OPTIONS_LENGTH = 1000;

    /**
     * User type representing a {@link UserHandle#USER_SYSTEM system} user that is a human user.
     * This type of user cannot be created; it can only pre-exist on first boot.
     * @hide
     */
    @SystemApi
    public static final String USER_TYPE_FULL_SYSTEM = "android.os.usertype.full.SYSTEM";

    /**
     * User type representing a regular non-profile non-{@link UserHandle#USER_SYSTEM system} human
     * user.
     * This is sometimes called an ordinary 'secondary user'.
     * @hide
     */
    @SystemApi
    public static final String USER_TYPE_FULL_SECONDARY = "android.os.usertype.full.SECONDARY";

    /**
     * User type representing a guest user that may be transient.
     * @hide
     */
    @SystemApi
    public static final String USER_TYPE_FULL_GUEST = "android.os.usertype.full.GUEST";

    /**
     * User type representing a user for demo purposes only, which can be removed at any time.
     * @hide
     */
    public static final String USER_TYPE_FULL_DEMO = "android.os.usertype.full.DEMO";

    /**
     * User type representing a "restricted profile" user, which is a full user that is subject to
     * certain restrictions from a parent user. Note, however, that it is NOT technically a profile.
     * @hide
     */
    public static final String USER_TYPE_FULL_RESTRICTED = "android.os.usertype.full.RESTRICTED";

    /**
     * User type representing a managed profile, which is a profile that is to be managed by a
     * device policy controller (DPC).
     * The intended purpose is for work profiles, which are managed by a corporate entity.
     * @hide
     */
    @SystemApi
    public static final String USER_TYPE_PROFILE_MANAGED = "android.os.usertype.profile.MANAGED";

    /**
     * User type representing a clone profile. Clone profile is a user profile type used to run
     * second instance of an otherwise single user App (eg, messengers). Currently only the
     * {@link android.content.pm.UserInfo#isMain()} user can have a clone profile.
     *
     * @hide
     */
    @SystemApi
    public static final String USER_TYPE_PROFILE_CLONE = "android.os.usertype.profile.CLONE";


    /**
     * User type representing a private profile.
     * @hide
     */
    @FlaggedApi(android.os.Flags.FLAG_ALLOW_PRIVATE_PROFILE)
    @TestApi
    public static final String USER_TYPE_PROFILE_PRIVATE = "android.os.usertype.profile.PRIVATE";

    /**
     * User type representing a generic profile for testing purposes. Only on debuggable builds.
     * @hide
     */
    public static final String USER_TYPE_PROFILE_TEST = "android.os.usertype.profile.TEST";

    /**
     * User type representing a communal profile, which is shared by all users of the device.
     * @hide
     */
    public static final String USER_TYPE_PROFILE_COMMUNAL = "android.os.usertype.profile.COMMUNAL";

    /**
     * User type representing a {@link UserHandle#USER_SYSTEM system} user that is <b>not</b> a
     * human user.
     * This type of user cannot be created; it can only pre-exist on first boot.
     * @hide
     */
    @SystemApi
    public static final String USER_TYPE_SYSTEM_HEADLESS = "android.os.usertype.system.HEADLESS";

    /**
     * Flag passed to {@link #requestQuietModeEnabled} to request disabling quiet mode only if
     * there is no need to confirm the user credentials. If credentials are required to disable
     * quiet mode, {@link #requestQuietModeEnabled} will do nothing and return {@code false}.
     */
    public static final int QUIET_MODE_DISABLE_ONLY_IF_CREDENTIAL_NOT_REQUIRED = 0x1;

    /**
     * Flag passed to {@link #requestQuietModeEnabled} to request disabling quiet mode without
     * asking for credentials. This is used when managed profile password is forgotten. It starts
     * the user in locked state so that a direct boot aware DPC could reset the password.
     * Should not be used together with
     * {@link #QUIET_MODE_DISABLE_ONLY_IF_CREDENTIAL_NOT_REQUIRED} or an exception will be thrown.
     * This flag is currently only allowed for {@link #isManagedProfile() managed profiles};
     * usage on other profiles may result in an Exception.
     * @hide
     */
    public static final int QUIET_MODE_DISABLE_DONT_ASK_CREDENTIAL = 0x2;

    /**
     * List of flags available for the {@link #requestQuietModeEnabled} method.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "QUIET_MODE_" }, value = {
            QUIET_MODE_DISABLE_ONLY_IF_CREDENTIAL_NOT_REQUIRED,
            QUIET_MODE_DISABLE_DONT_ASK_CREDENTIAL})
    public @interface QuietModeFlag {}

    /**
     * @hide
     * No user restriction.
     */
    @SystemApi
    public static final int RESTRICTION_NOT_SET = 0x0;

    /**
     * @hide
     * User restriction set by system/user.
     */
    @SystemApi
    public static final int RESTRICTION_SOURCE_SYSTEM = 0x1;

    /**
     * @hide
     * User restriction set by a device owner.
     */
    @SystemApi
    public static final int RESTRICTION_SOURCE_DEVICE_OWNER = 0x2;

    /**
     * @hide
     * User restriction set by a profile owner.
     */
    @SystemApi
    public static final int RESTRICTION_SOURCE_PROFILE_OWNER = 0x4;

    /** @removed mistakenly exposed as system-api previously */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "RESTRICTION_" }, value = {
            RESTRICTION_NOT_SET,
            RESTRICTION_SOURCE_SYSTEM,
            RESTRICTION_SOURCE_DEVICE_OWNER,
            RESTRICTION_SOURCE_PROFILE_OWNER
    })
    public @interface UserRestrictionSource {}

    /**
     * Specifies if a user is disallowed from adding and removing accounts, unless they are
     * {@link android.accounts.AccountManager#addAccountExplicitly programmatically} added by
     * Authenticator.
     * The default value is <code>false</code>.
     *
     * <p>From {@link android.os.Build.VERSION_CODES#N} a profile or device owner app can still
     * use {@link android.accounts.AccountManager} APIs to add or remove accounts when account
     * management is disallowed.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_ACCOUNT_MANAGEMENT}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_MODIFY_ACCOUNTS = "no_modify_accounts";

    /**
     * Specifies if a user is disallowed from changing Wi-Fi access points via Settings. This
     * restriction does not affect Wi-Fi tethering settings.
     *
     * <p>A device owner and a profile owner can set this restriction, although the restriction has
     * no effect in a managed profile. When it is set by a device owner, a profile owner on the
     * primary user or by a profile owner of an organization-owned managed profile on the parent
     * profile, it disallows the primary user from changing Wi-Fi access points.
     *
     * <p>Holders of the permission {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_WIFI}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>The default value is <code>false</code>.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_CONFIG_WIFI = "no_config_wifi";

    /**
     * Specifies if a user is disallowed from enabling/disabling Wi-Fi.
     *
     * <p>This restriction can only be set by a device owner,
     * a profile owner of an organization-owned managed profile on the parent profile.
     * When it is set by any of these owners, it applies globally - i.e., it disables airplane mode
     * from changing Wi-Fi state.
     *
     * <p>Holders of the permission {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_WIFI}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>The default value is <code>false</code>.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_CHANGE_WIFI_STATE = "no_change_wifi_state";

    /**
     * Specifies if a user is disallowed from using Wi-Fi tethering.
     *
     * <p>This restriction does not limit the user's ability to modify or connect to regular
     * Wi-Fi networks, which is separately controlled by {@link #DISALLOW_CONFIG_WIFI}.
     *
     * <p>This restriction can only be set by a device owner,
     * a profile owner of an organization-owned managed profile on the parent profile.
     * When it is set by any of these owners, it prevents all users from using
     * Wi-Fi tethering. Other forms of tethering are not affected.
     *
     * <p>Holders of the permission {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_WIFI}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * This user restriction disables only Wi-Fi tethering.
     * Use {@link #DISALLOW_CONFIG_TETHERING} to limit all forms of tethering.
     * When {@link #DISALLOW_CONFIG_TETHERING} is set, this user restriction becomes obsolete.
     *
     * <p>The default value is <code>false</code>.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_WIFI_TETHERING = "no_wifi_tethering";

    /**
     * Specifies if a user is disallowed from being granted admin privileges.
     *
     * <p>This restriction limits ability of other admin users to grant admin
     * privileges to selected user.
     *
     * <p>This restriction has no effect in a mode that does not allow multiple admins.
     *
     * <p>The default value is <code>false</code>.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_GRANT_ADMIN = "no_grant_admin";

    /**
     * Specifies if users are disallowed from sharing Wi-Fi for admin configured networks.
     *
     * <p>Device owner and profile owner can set this restriction.
     * When it is set by any of these owners, it prevents all users from
     * sharing Wi-Fi for networks configured by these owners.
     * Other networks not configured by these owners are not affected.
     *
     * <p>Holders of the permission {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_WIFI}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>The default value is <code>false</code>.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_SHARING_ADMIN_CONFIGURED_WIFI =
            "no_sharing_admin_configured_wifi";

    /**
     * Specifies if a user is disallowed from using Wi-Fi Direct.
     *
     * <p>This restriction can only be set by a device owner,
     * a profile owner of an organization-owned managed profile on the parent profile.
     * When it is set by any of these owners, it prevents all users from using
     * Wi-Fi Direct.
     *
     * <p>Holders of the permission {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_WIFI}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>The default value is <code>false</code>.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_WIFI_DIRECT = "no_wifi_direct";

    /**
     * Specifies if a user is disallowed from adding a new Wi-Fi configuration.
     *
     * <p>This restriction can only be set by a device owner,
     * a profile owner of an organization-owned managed profile on the parent profile.
     * When it is set by any of these owners, it prevents all users from adding
     * a new Wi-Fi configuration. This does not limit the owner and carrier's ability
     * to add a new configuration.
     *
     * <p>Holders of the permission {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_WIFI}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>The default value is <code>false</code>.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_ADD_WIFI_CONFIG = "no_add_wifi_config";

    /**
     * Specifies if a user is disallowed from changing the device
     * language. The default value is <code>false</code>.
     *
     * <p>Holders of the permission {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_LOCALE}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_CONFIG_LOCALE = "no_config_locale";

    /**
     * Specifies if a user is disallowed from installing applications. This user restriction also
     * prevents device owners and profile owners installing apps. The default value is
     * {@code false}.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_APPS_CONTROL}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_INSTALL_APPS = "no_install_apps";

    /**
     * Specifies if a user is disallowed from uninstalling applications.
     * The default value is <code>false</code>.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_APPS_CONTROL}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_UNINSTALL_APPS = "no_uninstall_apps";

    /**
     * Specifies if a user is disallowed from turning on location sharing.
     *
     * <p>In a managed profile, location sharing by default reflects the primary user's setting, but
     * can be overridden and forced off by setting this restriction to true in the managed profile.
     *
     * <p>A device owner and a profile owner can set this restriction. When it is set by a device
     * owner, a profile owner on the primary user or by a profile owner of an organization-owned
     * managed profile on the parent profile, it prevents the primary user from turning on
     * location sharing.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_LOCATION}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>The default value is <code>false</code>.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_SHARE_LOCATION = "no_share_location";

    /**
     * Specifies if airplane mode is disallowed on the device.
     *
     * <p>This restriction can only be set by a device owner, a profile owner on the primary
     * user or a profile owner of an organization-owned managed profile on the parent profile.
     * When it is set by any of these owners, it applies globally - i.e., it disables airplane mode
     * on the entire device.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_AIRPLANE_MODE}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>The default value is <code>false</code>.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_AIRPLANE_MODE = "no_airplane_mode";

    /**
     * Specifies if a user is disallowed from configuring brightness. When device owner sets it,
     * it'll only be applied on the target(system) user.
     *
     * <p>The default value is <code>false</code>.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_DISPLAY}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>This user restriction has no effect on managed profiles.
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_CONFIG_BRIGHTNESS = "no_config_brightness";

    /**
     * Specifies if ambient display is disallowed for the user.
     *
     * <p>The default value is <code>false</code>.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_DISPLAY}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>This user restriction has no effect on managed profiles.
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_AMBIENT_DISPLAY = "no_ambient_display";

    /**
     * Specifies if a user is disallowed from changing screen off timeout.
     *
     * <p>The default value is <code>false</code>.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_DISPLAY}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>This user restriction has no effect on managed profiles.
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_CONFIG_SCREEN_TIMEOUT = "no_config_screen_timeout";

    /**
     * Specifies if a user is disallowed from enabling the
     * "Unknown Sources" setting, that allows installation of apps from unknown sources.
     * Unknown sources exclude adb and special apps such as trusted app stores.
     * The default value is <code>false</code>.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_INSTALL_UNKNOWN_SOURCES}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_INSTALL_UNKNOWN_SOURCES = "no_install_unknown_sources";

    /**
     * This restriction is a device-wide version of {@link #DISALLOW_INSTALL_UNKNOWN_SOURCES}.
     *
     * Specifies if all users on the device are disallowed from enabling the
     * "Unknown Sources" setting, that allows installation of apps from unknown sources.
     *
     * This restriction can be enabled by the profile owner, in which case all accounts and
     * profiles will be affected.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_INSTALL_UNKNOWN_SOURCES}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * The default value is <code>false</code>.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY =
            "no_install_unknown_sources_globally";

    /**
     * Specifies if a user is disallowed from configuring bluetooth via Settings. This does
     * <em>not</em> restrict the user from turning bluetooth on or off.
     *
     * <p>This restriction doesn't prevent the user from using bluetooth. For disallowing usage of
     * bluetooth completely on the device, use {@link #DISALLOW_BLUETOOTH}.
     *
     * <p>A device owner and a profile owner can set this restriction, although the restriction has
     * no effect in a managed profile. When it is set by a device owner, a profile owner on the
     * primary user or by a profile owner of an organization-owned managed profile on the parent
     * profile, it disallows the primary user from configuring bluetooth.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_BLUETOOTH}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>The default value is <code>false</code>.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_CONFIG_BLUETOOTH = "no_config_bluetooth";

    /**
     * Specifies if bluetooth is disallowed on the device. If bluetooth is disallowed on the device,
     * bluetooth cannot be turned on or configured via Settings.
     *
     * <p>This restriction can only be set by a device owner, a profile owner on the primary
     * user or a profile owner of an organization-owned managed profile on the parent profile.
     * When it is set by a device owner, it applies globally - i.e., it disables bluetooth on
     * the entire device and all users will be affected. When it is set by a profile owner on the
     * primary user or by a profile owner of an organization-owned managed profile on the parent
     * profile, it disables the primary user from using bluetooth and configuring bluetooth
     * in Settings.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_BLUETOOTH}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>The default value is <code>false</code>.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_BLUETOOTH = "no_bluetooth";

    /**
     * Specifies if outgoing bluetooth sharing is disallowed.
     *
     * <p>A device owner and a profile owner can set this restriction. When it is set by a device
     * owner, it applies globally. When it is set by a profile owner on the primary user or by a
     * profile owner of an organization-owned managed profile on the parent profile, it disables
     * the primary user from any outgoing bluetooth sharing.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_BLUETOOTH}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>Default is <code>true</code> for managed profiles and false otherwise.
     *
     * <p>When a device upgrades to {@link android.os.Build.VERSION_CODES#O}, the system sets it
     * for all existing managed profiles.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_BLUETOOTH_SHARING = "no_bluetooth_sharing";

    /**
     * Specifies if a user is disallowed from transferring files over USB.
     *
     * <p>This restriction can only be set by a <a href="https://developers.google.com/android/work/terminology#device_owner_do">
     * device owner</a> or a <a href="https://developers.google.com/android/work/terminology#profile_owner_po">
     * profile owner</a> on the primary user's profile or a profile owner of an organization-owned
     * <a href="https://developers.google.com/android/work/terminology#managed_profile">
     * managed profile</a> on the parent profile.
     * When it is set by a device owner, it applies globally. When it is set by a profile owner
     * on the primary user or by a profile owner of an organization-owned managed profile on
     * the parent profile, it disables the primary user from transferring files over USB. No other
     * user on the device is able to use file transfer over USB because the UI for file transfer
     * is always associated with the primary user.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_USB_FILE_TRANSFER}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>The default value is <code>false</code>.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_USB_FILE_TRANSFER = "no_usb_file_transfer";

    /**
     * Specifies if a user is disallowed from configuring user
     * credentials. The default value is <code>false</code>.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_LOCK_CREDENTIALS}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_CONFIG_CREDENTIALS = "no_config_credentials";

    /**
     * When set on the admin user this specifies if the user can remove users.
     * When set on a non-admin secondary user, this specifies if the user can remove itself.
     * This restriction has no effect on managed profiles.
     * The default value is <code>false</code>.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_MODIFY_USERS}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_REMOVE_USER = "no_remove_user";

    /**
     * Specifies if managed profiles of this user can be removed, other than by its profile owner.
     * The default value is <code>false</code>.
     * <p>
     * This restriction has no effect on managed profiles.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     * @deprecated As the ability to have a managed profile on a fully-managed device has been
     * removed from the platform, this restriction will be silently ignored when applied by the
     * device owner.
     * When the device is provisioned with a managed profile on an organization-owned device,
     * the managed profile could not be removed anyway.
     */
    @Deprecated
    public static final String DISALLOW_REMOVE_MANAGED_PROFILE = "no_remove_managed_profile";

    /**
     * Specifies if a user is disallowed from enabling or accessing debugging features.
     *
     * <p>A device owner and a profile owner can set this restriction. When it is set by a device
     * owner, a profile owner on the primary user or by a profile owner of an organization-owned
     * managed profile on the parent profile, it disables debugging features altogether, including
     * USB debugging. When set on a managed profile or a secondary user, it blocks debugging for
     * that user only, including starting activities, making service calls, accessing content
     * providers, sending broadcasts, installing/uninstalling packages, clearing user data, etc.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_DEBUGGING_FEATURES}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>The default value is <code>false</code>.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_DEBUGGING_FEATURES = "no_debugging_features";

    /**
     * Specifies if a user is disallowed from configuring a VPN. The default value is
     * <code>false</code>. This restriction has an effect when set by device owners and, in Android
     * 6.0 ({@linkplain android.os.Build.VERSION_CODES#M API level 23}) or higher, profile owners.
     * <p>This restriction also prevents VPNs from starting. However, in Android 7.0
     * ({@linkplain android.os.Build.VERSION_CODES#N API level 24}) or higher, the system does
     * start always-on VPNs created by the device or profile owner.
     * <p>From Android 12 ({@linkplain android.os.Build.VERSION_CODES#S API level 31}) enforcing
     * this restriction clears currently active VPN if it was configured by the user.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_VPN}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_CONFIG_VPN = "no_config_vpn";

    /**
     * Specifies if a user is disallowed from enabling or disabling location providers. As a
     * result, user is disallowed from turning on or off location via Settings.
     *
     * <p>A device owner and a profile owner can set this restriction. When it is set by a device
     * owner, a profile owner on the primary user or by a profile owner of an organization-owned
     * managed profile on the parent profile, it disallows the primary user from turning location
     * on or off.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_LOCATION}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>The default value is <code>false</code>.
     *
     * <p>This user restriction is different from {@link #DISALLOW_SHARE_LOCATION},
     * as a device owner or a profile owner can still enable or disable location mode via
     * {@link DevicePolicyManager#setLocationEnabled} when this restriction is on.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see LocationManager#isLocationEnabled()
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_CONFIG_LOCATION = "no_config_location";

    /**
     * Specifies configuring date, time and timezone is disallowed via Settings.
     *
     * <p>A device owner and a profile owner can set this restriction, although the restriction has
     * no effect in a managed profile. When it is set by a device owner or by a profile owner of an
     * organization-owned managed profile on the parent profile, it applies globally - i.e.,
     * it disables date, time and timezone setting on the entire device and all users are affected.
     * When it is set by a profile owner on the primary user, it disables the primary user
     * from configuring date, time and timezone and disables all configuring of date, time and
     * timezone in Settings.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_TIME}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>The default value is <code>false</code>.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_CONFIG_DATE_TIME = "no_config_date_time";

    /**
     * Specifies if a user is disallowed from using and configuring Tethering and portable hotspots
     * via Settings.
     *
     * <p>This restriction can only be set by a device owner, a profile owner on the primary
     * user or a profile owner of an organization-owned managed profile on the parent profile.
     * When it is set by a device owner, it applies globally. When it is set by a profile owner
     * on the primary user or by a profile owner of an organization-owned managed profile on
     * the parent profile, it disables the primary user from using Tethering and hotspots and
     * disables all configuring of Tethering and hotspots in Settings.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_MOBILE_NETWORK}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>The default value is <code>false</code>.
     *
     * <p>In Android 9.0 or higher, if tethering is enabled when this restriction is set,
     * tethering will be automatically turned off.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_CONFIG_TETHERING = "no_config_tethering";

    /**
     * Specifies if a user is disallowed from resetting network settings
     * from Settings. This can only be set by device owners and profile owners on the primary user.
     * The default value is <code>false</code>.
     * <p>This restriction has no effect on secondary users and managed profiles since only the
     * primary user can reset the network settings of the device.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_MOBILE_NETWORK}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_NETWORK_RESET = "no_network_reset";

    /**
     * Specifies if a user is disallowed from factory resetting from Settings.
     * This can only be set by device owners and profile owners on an admin user.
     * The default value is <code>false</code>.
     * <p>This restriction has no effect on non-admin users since they cannot factory reset the
     * device.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_FACTORY_RESET}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_FACTORY_RESET = "no_factory_reset";

    /**
     * Specifies if a user is disallowed from adding new users. This can only be set by device
     * owners or profile owners on the primary user. The default value is <code>false</code>.
     * <p>This restriction has no effect on secondary users and managed profiles since only the
     * primary user can add other users.
     * <p> When the device is an organization-owned device provisioned with a managed profile,
     * this restriction will be set as a base restriction which cannot be removed by any admin.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_MODIFY_USERS}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_ADD_USER = "no_add_user";

    /**
     * Specifies if a user is disallowed from adding managed profiles.
     * <p>The default value for an unmanaged user is <code>false</code>.
     * For users with a device owner set, the default is <code>true</code>.
     * <p>This restriction has no effect on managed profiles.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     * @deprecated As the ability to have a managed profile on a fully-managed device has been
     * removed from the platform, this restriction will be silently ignored when applied by the
     * device owner.
     */
    @Deprecated
    public static final String DISALLOW_ADD_MANAGED_PROFILE = "no_add_managed_profile";

    /**
     * Specifies if a user is disallowed from creating clone profile.
     * <p>The default value for an unmanaged user is <code>false</code>.
     * For users with a device owner set, the default is <code>true</code>.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_PROFILES}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     * @hide
     */
    public static final String DISALLOW_ADD_CLONE_PROFILE = "no_add_clone_profile";

    /**
     * Specifies if a user is disallowed from creating a private profile.
     * <p>The default value for an unmanaged user is <code>false</code>.
     * For users with a device owner set, the default is <code>true</code>.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_PROFILES}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     * @hide
     */
    public static final String DISALLOW_ADD_PRIVATE_PROFILE = "no_add_private_profile";

    /**
     * Specifies if a user is disallowed from disabling application verification. The default
     * value is <code>false</code>.
     *
     * <p>In Android 8.0 ({@linkplain android.os.Build.VERSION_CODES#O API level 26}) and higher,
     * this is a global user restriction. If a device owner or profile owner sets this restriction,
     * the system enforces app verification across all users on the device. Running in earlier
     * Android versions, this restriction affects only the profile that sets it.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_INSTALL_UNKNOWN_SOURCES}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String ENSURE_VERIFY_APPS = "ensure_verify_apps";

    /**
     * Specifies if a user is disallowed from configuring cell broadcasts.
     *
     * <p>This restriction can only be set by a device owner, a profile owner on the primary
     * user or a profile owner of an organization-owned managed profile on the parent profile.
     * When it is set by a device owner, it applies globally. When it is set by a profile owner
     * on the primary user or by a profile owner of an organization-owned managed profile on
     * the parent profile, it disables the primary user from configuring cell broadcasts.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_MOBILE_NETWORK}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>The default value is <code>false</code>.
     *
     * <p>This restriction has no effect on secondary users and managed profiles since only the
     * primary user can configure cell broadcasts.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_CONFIG_CELL_BROADCASTS = "no_config_cell_broadcasts";

    /**
     * Specifies if a user is disallowed from configuring mobile networks.
     *
     * <p>This restriction can only be set by a device owner, a profile owner on the primary
     * user or a profile owner of an organization-owned managed profile on the parent profile.
     * When it is set by a device owner, it applies globally. When it is set by a profile owner
     * on the primary user or by a profile owner of an organization-owned managed profile on
     * the parent profile, it disables the primary user from configuring mobile networks.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_MOBILE_NETWORK}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>The default value is <code>false</code>.
     *
     * <p>This restriction has no effect on secondary users and managed profiles since only the
     * primary user can configure mobile networks.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_CONFIG_MOBILE_NETWORKS = "no_config_mobile_networks";

    /**
     * Specifies if a user is disallowed from modifying
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
     *
     * <p><strong>Note:</strong> The user will still be able to perform those actions via other
     * means (such as adb). Third party apps will also be able to uninstall apps via the
     * {@link android.content.pm.PackageInstaller}. {@link #DISALLOW_UNINSTALL_APPS} or
     * {@link DevicePolicyManager#setUninstallBlocked(ComponentName, String, boolean)} should be
     * used to prevent the user from uninstalling apps completely, and
     * {@link DevicePolicyManager#addPersistentPreferredActivity(ComponentName, IntentFilter, ComponentName)}
     * to add a default intent handler for a given intent filter.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_APPS_CONTROL}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_APPS_CONTROL = "no_control_apps";

    /**
     * Specifies if a user is disallowed from mounting physical external media.
     *
     * <p>This restriction can only be set by a device owner, a profile owner on the primary
     * user or a profile owner of an organization-owned managed profile on the parent profile.
     * When it is set by a device owner, it applies globally. When it is set by a profile owner
     * on the primary user or by a profile owner of an organization-owned managed profile on
     * the parent profile, it disables the primary user from mounting physical external media.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_PHYSICAL_MEDIA}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>The default value is <code>false</code>.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_MOUNT_PHYSICAL_MEDIA = "no_physical_media";

    /**
     * Specifies if a user is disallowed from adjusting microphone volume. If set, the microphone
     * will be muted.
     *
     * <p>A device owner and a profile owner can set this restriction, although the restriction has
     * no effect in a managed profile. When it is set by a device owner, it applies globally. When
     * it is set by a profile owner on the primary user or by a profile owner of an
     * organization-owned managed profile on the parent profile, it will disallow the primary user
     * from adjusting the microphone volume.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_MICROPHONE}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>The default value is <code>false</code>.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_UNMUTE_MICROPHONE = "no_unmute_microphone";

    /**
     * Specifies if a user is disallowed from adjusting the global volume. If set, the global volume
     * will be muted. This can be set by device owners from API 21 and profile owners from API 24.
     * The default value is <code>false</code>.
     *
     * <p>When the restriction is set by profile owners, then it only applies to relevant
     * profiles.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_AUDIO_OUTPUT}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>This restriction has no effect on managed profiles.
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_ADJUST_VOLUME = "no_adjust_volume";

    /**
     * Specifies that the user is not allowed to make outgoing phone calls. Emergency calls are
     * still permitted.
     *
     * <p>A device owner and a profile owner can set this restriction, although the restriction has
     * no effect in a managed profile. When it is set by a device owner, a profile owner on the
     * primary user or by a profile owner of an organization-owned managed profile on the parent
     * profile, it disallows the primary user from making outgoing phone calls.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_CALLS}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>The default value is <code>false</code>.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_OUTGOING_CALLS = "no_outgoing_calls";

    /**
     * Specifies that the user is not allowed to send or receive SMS messages.
     *
     * <p>This restriction can only be set by a device owner, a profile owner on the primary
     * user or a profile owner of an organization-owned managed profile on the parent profile.
     * When it is set by a device owner, it applies globally. When it is set by a profile owner
     * on the primary user or by a profile owner of an organization-owned managed profile on
     * the parent profile, it disables the primary user from sending or receiving SMS messages.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_SMS}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>The default value is <code>false</code>.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_SMS = "no_sms";

    /**
     * Specifies if the user is not allowed to have fun. In some cases, the
     * device owner may wish to prevent the user from experiencing amusement or
     * joy while using the device. The default value is <code>false</code>.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_FUN}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_FUN = "no_fun";

    /**
     * Specifies that windows besides app windows should not be
     * created. This will block the creation of the following types of windows.
     * <li>{@link LayoutParams#TYPE_TOAST}</li>
     * <li>{@link LayoutParams#TYPE_APPLICATION_OVERLAY}</li>
     *
     * <p>This can only be set by device owners and profile owners on the primary user.
     * The default value is <code>false</code>.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_WINDOWS}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_CREATE_WINDOWS = "no_create_windows";

    /**
     * Specifies that system error dialogs for crashed or unresponsive apps should not be shown.
     * In this case, the system will force-stop the app as if the user chooses the "close app"
     * option on the UI. A feedback report isn't collected as there is no way for the user to
     * provide explicit consent. The default value is <code>false</code>.
     *
     * <p>When this user restriction is set by device owners, it's applied to all users. When set by
     * the profile owner of the primary user or a secondary user, the restriction affects only the
     * calling user. This user restriction has no effect on managed profiles.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_SYSTEM_DIALOGS}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_SYSTEM_ERROR_DIALOGS = "no_system_error_dialogs";

    /**
     * Specifies if the clipboard contents can be exported by pasting the data into other users or
     * profiles. This restriction doesn't prevent import, such as someone pasting clipboard data
     * from other profiles or users. The default value is {@code false}.
     *
     * <p><strong>Note</strong>: Because it's possible to extract data from screenshots using
     * optical character recognition (OCR), we strongly recommend combining this user restriction
     * with {@link DevicePolicyManager#setScreenCaptureDisabled(ComponentName, boolean)}.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_PROFILE_INTERACTION}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_CROSS_PROFILE_COPY_PASTE = "no_cross_profile_copy_paste";

    /**
     * Specifies if the user is not allowed to use NFC to beam out data from apps.
     * The default value is <code>false</code>.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_NEARBY_COMMUNICATION}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_OUTGOING_BEAM = "no_outgoing_beam";

    /**
     * Hidden user restriction to disallow access to wallpaper manager APIs. This restriction
     * generally means that wallpapers are not supported for the particular user. This user
     * restriction is always set for managed profiles, because such profiles don't have wallpapers.
     * @hide
     * @see #DISALLOW_SET_WALLPAPER
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_WALLPAPER = "no_wallpaper";

    /**
     * User restriction to disallow setting a wallpaper. Profile owner and device owner
     * are able to set wallpaper regardless of this restriction.
     * The default value is <code>false</code>.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_WALLPAPER}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_SET_WALLPAPER = "no_set_wallpaper";

    /**
     * Specifies if the user is not allowed to reboot the device into safe boot mode.
     *
     * <p>This restriction can only be set by a device owner, a profile owner on the primary
     * user or a profile owner of an organization-owned managed profile on the parent profile.
     * When it is set by a device owner, it applies globally. When it is set by a profile owner
     * on the primary user or by a profile owner of an organization-owned managed profile on
     * the parent profile, it disables the primary user from rebooting the device into safe
     * boot mode.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_SAFE_BOOT}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>The default value is <code>false</code>.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_SAFE_BOOT = "no_safe_boot";

    /**
     * Specifies if a user is not allowed to record audio. This restriction is always enabled for
     * background users. The default value is <code>false</code>.
     *
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final String DISALLOW_RECORD_AUDIO = "no_record_audio";

    /**
     * Specifies if a user is not allowed to run in the background and should be stopped during
     * user switch. The default value is <code>false</code>.
     *
     * <p>This restriction can be set by device owners and profile owners.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_RUN_IN_BACKGROUND}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     * @hide
     */
    @SystemApi
    public static final String DISALLOW_RUN_IN_BACKGROUND = "no_run_in_background";

    /**
     * Specifies if a user is not allowed to use the camera.
     *
     * <p>A device owner and a profile owner can set this restriction. When it is set by a
     * device owner, it applies globally - i.e., it disables the use of camera on the entire device
     * and all users are affected. When it is set by a profile owner on the primary user or by a
     * profile owner of an organization-owned managed profile on the parent profile, it disables
     * the primary user from using camera.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_CAMERA}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>The default value is <code>false</code>.
     *
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     * @hide
     */
    public static final String DISALLOW_CAMERA = "no_camera";

    /**
     * Specifies if a user is not allowed to unmute the device's global volume.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_AUDIO_OUTPUT}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * @see DevicePolicyManager#setMasterVolumeMuted(ComponentName, boolean)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     * @hide
     */
    public static final String DISALLOW_UNMUTE_DEVICE = "disallow_unmute_device";

    /**
     * Specifies if a user is not allowed to use cellular data when roaming.
     *
     * <p>This restriction can only be set by a device owner, a profile owner on the primary
     * user or a profile owner of an organization-owned managed profile on the parent profile.
     * When it is set by a device owner, it applies globally. When it is set by a profile owner
     * on the primary user or by a profile owner of an organization-owned managed profile on
     * the parent profile, it disables the primary user from using cellular data when roaming.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_MOBILE_NETWORK}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>The default value is <code>false</code>.
     *
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_DATA_ROAMING = "no_data_roaming";

    /**
     * Specifies if a user is not allowed to change their icon. Device owner and profile owner
     * can set this restriction. When it is set by device owner, only the target user will be
     * affected. The default value is <code>false</code>.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_MODIFY_USERS}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_SET_USER_ICON = "no_set_user_icon";

    /**
     * Specifies if a user is not allowed to enable the oem unlock setting. The default value is
     * <code>false</code>. Setting this restriction has no effect if the bootloader is already
     * unlocked.
     *
     * <p>Not for use by third-party applications.
     *
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     * @deprecated use {@link OemLockManager#setOemUnlockAllowedByCarrier(boolean, byte[])} instead.
     * @hide
     */
    @Deprecated
    @SystemApi
    public static final String DISALLOW_OEM_UNLOCK = "no_oem_unlock";

    /**
     * Specifies that the managed profile is not allowed to have unified lock screen challenge with
     * the primary user.
     *
     * <p><strong>Note:</strong> Setting this restriction alone doesn't automatically set a
     * separate challenge. Profile owner can ask the user to set a new password using
     * {@link DevicePolicyManager#ACTION_SET_NEW_PASSWORD} and verify it using
     * {@link DevicePolicyManager#isUsingUnifiedPassword(ComponentName)}.
     *
     * <p>Can be set by profile owners. It only has effect on managed profiles when set by managed
     * profile owner. Has no effect on non-managed profiles or users.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_LOCK_CREDENTIALS}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_UNIFIED_PASSWORD = "no_unified_password";

    /**
     * Allows apps in the parent profile to handle web links from the managed profile.
     *
     * This user restriction has an effect only in a managed profile.
     * If set:
     * Intent filters of activities in the parent profile with action
     * {@link android.content.Intent#ACTION_VIEW},
     * category {@link android.content.Intent#CATEGORY_BROWSABLE}, scheme http or https, and which
     * define a host can handle intents from the managed profile.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_PROFILES}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>The default value is <code>false</code>.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String ALLOW_PARENT_PROFILE_APP_LINKING
            = "allow_parent_profile_app_linking";

    /**
     * Specifies if a user is not allowed to use Autofill Services.
     *
     * <p>Device owner and profile owner can set this restriction. When it is set by device owner,
     * only the target user will be affected.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_AUTOFILL}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>The default value is <code>false</code>.
     *
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_AUTOFILL = "no_autofill";

    /**
     * Specifies if the contents of a user's screen is not allowed to be captured for artificial
     * intelligence purposes.
     *
     * <p>A device owner and a profile owner can set this restriction. When it is set by a device
     * owner, a profile owner on the primary user or by a profile owner of an organization-owned
     * managed profile on the parent profile, it disables the primary user's screen from being
     * captured for artificial intelligence purposes.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_SCREEN_CONTENT}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>The default value is <code>false</code>.
     *
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_CONTENT_CAPTURE = "no_content_capture";

    /**
     * Specifies if the current user is able to receive content suggestions for selections based on
     * the contents of their screen.
     *
     * <p>A device owner and a profile owner can set this restriction. When it is set by a device
     * owner, a profile owner on the primary user or by a profile owner of an organization-owned
     * managed profile on the parent profile, it disables the primary user from receiving content
     * suggestions for selections based on the contents of their screen.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_SCREEN_CONTENT}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>The default value is <code>false</code>.
     *
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_CONTENT_SUGGESTIONS = "no_content_suggestions";

    /**
     * Specifies if user switching is blocked on the current user.
     *
     * <p> This restriction can only be set by the device owner, it will be applied to all users.
     * Device owner can still switch user via
     * {@link DevicePolicyManager#switchUser(ComponentName, UserHandle)} when this restriction is
     * set.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_MODIFY_USERS}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>The default value is <code>false</code>.
     *
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_USER_SWITCH = "no_user_switch";

    /**
     * Specifies whether the user can share file / picture / data from the primary user into the
     * managed profile, either by sending them from the primary side, or by picking up data within
     * an app in the managed profile.
     * <p>
     * When a managed profile is created, the system allows the user to send data from the primary
     * side to the profile by setting up certain default cross profile intent filters. If
     * this is undesired, this restriction can be set to disallow it. Note that this restriction
     * will not block any sharing allowed by explicit
     * {@link DevicePolicyManager#addCrossProfileIntentFilter} calls by the profile owner.
     * <p>
     * This restriction is only meaningful when set by profile owner. When it is set by device
     * owner, it does not have any effect.
     * <p>
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_PROFILE_INTERACTION}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>The default value is <code>false</code>.
     *
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_SHARE_INTO_MANAGED_PROFILE = "no_sharing_into_profile";

    /**
     * Specifies whether the user is allowed to print.
     *
     * This restriction can be set by device or profile owner.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_PRINTING}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * The default value is {@code false}.
     *
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_PRINTING = "no_printing";

    /**
     * Specifies whether the user is allowed to modify private DNS settings.
     *
     * <p>This restriction can only be set by a device owner or a profile owner of an
     * organization-owned managed profile on the parent profile. When it is set by either of these
     * owners, it applies globally.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_RESTRICT_PRIVATE_DNS}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>The default value is <code>false</code>.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_CONFIG_PRIVATE_DNS =
            "disallow_config_private_dns";

    /**
     * Specifies whether the microphone toggle is available to the user. If this restriction is set,
     * the user will not be able to block microphone access via the system toggle. If microphone
     * access is blocked when the restriction is added, it will be automatically re-enabled.
     *
     * This restriction can only be set by a device owner.
     *
     * <p>The default value is <code>false</code>.
     *
     * @see android.hardware.SensorPrivacyManager
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_MICROPHONE_TOGGLE =
            "disallow_microphone_toggle";

    /**
     * Specifies whether the camera toggle is available to the user. If this restriction is set,
     * the user will not be able to block camera access via the system toggle. If camera
     * access is blocked when the restriction is added, it will be automatically re-enabled.
     *
     * This restriction can only be set by a device owner.
     *
     * <p>The default value is <code>false</code>.
     *
     * @see android.hardware.SensorPrivacyManager
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_CAMERA_TOGGLE =
            "disallow_camera_toggle";

    /**
     * This is really not a user restriction in the normal sense. This can't be set to a user,
     * via UserManager nor via DevicePolicyManager. This is not even set in UserSettingsUtils.
     * This is defined here purely for convenience within the settings app.
     *
     * TODO(b/191306258): Refactor the Settings app to remove the need for this field, and delete it
     *
     * Specifies whether biometrics are available to the user. This is used internally only,
     * as a means of communications between biometric settings and
     * {@link com.android.settingslib.enterprise.ActionDisabledByAdminControllerFactory}.
     *
     * @see {@link android.hardware.biometrics.ParentalControlsUtilsInternal}
     * @see {@link com.android.settings.biometrics.ParentalControlsUtils}
     *
     * @hide
     */
    public static final String DISALLOW_BIOMETRIC = "disallow_biometric";

    /**
     * Specifies whether the user is allowed to modify default apps in settings.
     *
     * <p>This restriction can be set by device or profile owner.
     *
     * <p>The default value is <code>false</code>.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_CONFIG_DEFAULT_APPS = "disallow_config_default_apps";

    /**
     * Application restriction key that is used to indicate the pending arrival
     * of real restrictions for the app.
     *
     * <p>
     * Applications that support restrictions should check for the presence of this key.
     * A <code>true</code> value indicates that restrictions may be applied in the near
     * future but are not available yet. It is the responsibility of any
     * management application that sets this flag to update it when the final
     * restrictions are enforced.
     *
     * <p>Key for application restrictions.
     * <p>Type: Boolean
     * @see android.app.admin.DevicePolicyManager#setApplicationRestrictions(
     *      android.content.ComponentName, String, Bundle)
     * @see android.app.admin.DevicePolicyManager#getApplicationRestrictions(
     *      android.content.ComponentName, String)
     */
    public static final String KEY_RESTRICTIONS_PENDING = "restrictions_pending";

    /**
     * Specifies if a user is not allowed to use 2g networks.
     *
     * <p> This is a security feature. 2g has no mutual authentication between a device and
     * cellular base station and downgrading a device's connection to 2g is a common tactic for
     * several types of privacy and security compromising attacks that could allow an adversary
     * to intercept, inject, or modify cellular communications.
     *
     * <p>This restriction can only be set by a device owner or a profile owner of an
     * organization-owned managed profile on the parent profile.
     * In all cases, the setting applies globally on the device.
     *
     * <p> Cellular connectivity loss (where a device would have otherwise successfully
     * connected to a 2g network) occurs if the device is in an area where only 2g networks are
     * available. Emergency calls are an exception and are never impacted. The device will still
     * scan for and connect to a 2g network for emergency calls.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_MOBILE_NETWORK}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>The default value is <code>false</code>.
     *
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_CELLULAR_2G = "no_cellular_2g";

    /**
     * This user restriction specifies if Ultra-wideband is disallowed on the device. If
     * Ultra-wideband is disallowed it cannot be turned on via Settings.
     *
     * <p>
     * Ultra-wideband (UWB) is a radio technology that can use a very low energy level
     * for short-range, high-bandwidth communications over a large portion of the radio spectrum.
     *
     * <p>This restriction can only be set by a device owner or a profile owner of an
     * organization-owned managed profile on the parent profile.
     * In both cases, the restriction applies globally on the device and will turn off the
     * ultra-wideband radio if it's currently on and prevent the radio from being turned on in
     * the future.
     *
     * <p>Holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_NEARBY_COMMUNICATION}
     * can set this restriction using the DevicePolicyManager APIs mentioned below.
     *
     * <p>Default is <code>false</code>.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_ULTRA_WIDEBAND_RADIO = "no_ultra_wideband_radio";

    /**
     * This user restriction specifies if Near-field communication is disallowed on the device. If
     * Near-field communication is disallowed it cannot be turned on via Settings.
     *
     * <p>This restriction can only be set by a device owner or a profile owner of an
     * organization-owned managed profile on the parent profile.
     * In both cases, the restriction applies globally on the device and will turn off the
     * Near-field communication radio if it's currently on and prevent the radio from being turned
     * on in the future.
     *
     * <p>
     * Near-field communication (NFC) is a radio technology that allows two devices (like your phone
     * and a payments terminal) to communicate with each other when they're close together.
     *
     * <p>Default is <code>false</code>.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_USER_RESTRICTION)
    public static final String DISALLOW_NEAR_FIELD_COMMUNICATION_RADIO =
            "no_near_field_communication_radio";

    /**
     * This user restriction specifies if Thread network is disallowed on the device. If Thread
     * network is disallowed it cannot be turned on via Settings.
     *
     * <p>This restriction can only be set by a device owner or a profile owner of an
     * organization-owned managed profile on the parent profile.
     * In both cases, the restriction applies globally on the device and will turn off the
     * Thread network radio if it's currently on and prevent the radio from being turned
     * on in the future.
     *
     * <p> <a href="https://www.threadgroup.org">Thread</a> is a low-power and low-latency wireless
     * mesh networking protocol built on IPv6.
     *
     * <p>Default is <code>false</code>.
     *
     * <p>Key for user restrictions.
     * <p>Type: Boolean
     * @see DevicePolicyManager#addUserRestriction(ComponentName, String)
     * @see DevicePolicyManager#clearUserRestriction(ComponentName, String)
     * @see #getUserRestrictions()
     */
    @FlaggedApi("com.android.net.thread.flags.thread_user_restriction_enabled")
    public static final String DISALLOW_THREAD_NETWORK = "no_thread_network";

    /**
     * List of key values that can be passed into the various user restriction related methods
     * in {@link UserManager} & {@link DevicePolicyManager}.
     * Note: This is slightly different from the real set of user restrictions listed in {@link
     * com.android.server.pm.UserRestrictionsUtils#USER_RESTRICTIONS}. For example
     * {@link #KEY_RESTRICTIONS_PENDING} is not a real user restriction, but is a legitimate
     * value that can be passed into {@link #hasUserRestriction(String)}.
     * @hide
     */
    @StringDef(value = {
            DISALLOW_MODIFY_ACCOUNTS,
            DISALLOW_CONFIG_WIFI,
            DISALLOW_CONFIG_LOCALE,
            DISALLOW_INSTALL_APPS,
            DISALLOW_UNINSTALL_APPS,
            DISALLOW_SHARE_LOCATION,
            DISALLOW_AIRPLANE_MODE,
            DISALLOW_CONFIG_BRIGHTNESS,
            DISALLOW_AMBIENT_DISPLAY,
            DISALLOW_CONFIG_SCREEN_TIMEOUT,
            DISALLOW_INSTALL_UNKNOWN_SOURCES,
            DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
            DISALLOW_CONFIG_BLUETOOTH,
            DISALLOW_BLUETOOTH,
            DISALLOW_BLUETOOTH_SHARING,
            DISALLOW_USB_FILE_TRANSFER,
            DISALLOW_CONFIG_CREDENTIALS,
            DISALLOW_REMOVE_USER,
            DISALLOW_REMOVE_MANAGED_PROFILE,
            DISALLOW_DEBUGGING_FEATURES,
            DISALLOW_CONFIG_VPN,
            DISALLOW_CONFIG_LOCATION,
            DISALLOW_CONFIG_DATE_TIME,
            DISALLOW_CONFIG_TETHERING,
            DISALLOW_NETWORK_RESET,
            DISALLOW_FACTORY_RESET,
            DISALLOW_ADD_USER,
            DISALLOW_ADD_MANAGED_PROFILE,
            DISALLOW_ADD_CLONE_PROFILE,
            DISALLOW_ADD_PRIVATE_PROFILE,
            ENSURE_VERIFY_APPS,
            DISALLOW_CONFIG_CELL_BROADCASTS,
            DISALLOW_CONFIG_MOBILE_NETWORKS,
            DISALLOW_APPS_CONTROL,
            DISALLOW_MOUNT_PHYSICAL_MEDIA,
            DISALLOW_UNMUTE_MICROPHONE,
            DISALLOW_ADJUST_VOLUME,
            DISALLOW_OUTGOING_CALLS,
            DISALLOW_SMS,
            DISALLOW_FUN,
            DISALLOW_CREATE_WINDOWS,
            DISALLOW_SYSTEM_ERROR_DIALOGS,
            DISALLOW_CROSS_PROFILE_COPY_PASTE,
            DISALLOW_OUTGOING_BEAM,
            DISALLOW_WALLPAPER,
            DISALLOW_SET_WALLPAPER,
            DISALLOW_SAFE_BOOT,
            DISALLOW_RECORD_AUDIO,
            DISALLOW_RUN_IN_BACKGROUND,
            DISALLOW_CAMERA,
            DISALLOW_UNMUTE_DEVICE,
            DISALLOW_DATA_ROAMING,
            DISALLOW_SET_USER_ICON,
            DISALLOW_OEM_UNLOCK,
            DISALLOW_UNIFIED_PASSWORD,
            ALLOW_PARENT_PROFILE_APP_LINKING,
            DISALLOW_AUTOFILL,
            DISALLOW_CONTENT_CAPTURE,
            DISALLOW_CONTENT_SUGGESTIONS,
            DISALLOW_USER_SWITCH,
            DISALLOW_SHARE_INTO_MANAGED_PROFILE,
            DISALLOW_PRINTING,
            DISALLOW_CONFIG_PRIVATE_DNS,
            DISALLOW_MICROPHONE_TOGGLE,
            DISALLOW_CAMERA_TOGGLE,
            KEY_RESTRICTIONS_PENDING,
            DISALLOW_BIOMETRIC,
            DISALLOW_CHANGE_WIFI_STATE,
            DISALLOW_WIFI_TETHERING,
            DISALLOW_SHARING_ADMIN_CONFIGURED_WIFI,
            DISALLOW_WIFI_DIRECT,
            DISALLOW_ADD_WIFI_CONFIG,
            DISALLOW_CELLULAR_2G,
            DISALLOW_ULTRA_WIDEBAND_RADIO,
            DISALLOW_GRANT_ADMIN,
            DISALLOW_NEAR_FIELD_COMMUNICATION_RADIO,
            DISALLOW_THREAD_NETWORK,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface UserRestrictionKey {}

    /**
     * Property used to override whether the device uses headless system user mode.
     *
     * <p>Only used on non-user builds.
     *
     * <p><b>NOTE: </b>setting this variable directly won't properly change the headless system user
     * mode behavior and might put the device in a bad state; the system user mode should be changed
     * using {@code cmd user set-system-user-mode-emulation} instead.
     *
     * @hide
     */
    public static final String SYSTEM_USER_MODE_EMULATION_PROPERTY =
            "persist.debug.user_mode_emulation";

    /** @hide */
    public static final String SYSTEM_USER_MODE_EMULATION_DEFAULT = "default";
    /** @hide */
    public static final String SYSTEM_USER_MODE_EMULATION_FULL = "full";
    /** @hide */
    public static final String SYSTEM_USER_MODE_EMULATION_HEADLESS = "headless";

    /**
     * System Property used to override whether users can be created even if their type is disabled
     * or their limit is reached. Set value to 1 to enable.
     *
     * <p>Only used on non-user builds.
     *
     * @hide
     */
    public static final String DEV_CREATE_OVERRIDE_PROPERTY = "debug.user.creation_override";

    private static final String ACTION_CREATE_USER = "android.os.action.CREATE_USER";

    /**
     * Action to start an activity to create a supervised user.
     * Only devices with non-empty config_supervisedUserCreationPackage support this.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_USERS)
    public static final String ACTION_CREATE_SUPERVISED_USER =
            "android.os.action.CREATE_SUPERVISED_USER";

    /**
     * Extra containing a name for the user being created. Optional parameter passed to
     * ACTION_CREATE_USER activity.
     * @hide
     */
    public static final String EXTRA_USER_NAME = "android.os.extra.USER_NAME";

    /**
     * Extra containing account name for the user being created. Optional parameter passed to
     * ACTION_CREATE_USER activity.
     * @hide
     */
    public static final String EXTRA_USER_ACCOUNT_NAME = "android.os.extra.USER_ACCOUNT_NAME";

    /**
     * Extra containing account type for the user being created. Optional parameter passed to
     * ACTION_CREATE_USER activity.
     * @hide
     */
    public static final String EXTRA_USER_ACCOUNT_TYPE = "android.os.extra.USER_ACCOUNT_TYPE";

    /**
     * Extra containing account-specific data for the user being created. Optional parameter passed
     * to ACTION_CREATE_USER activity.
     * @hide
     */
    public static final String EXTRA_USER_ACCOUNT_OPTIONS
            = "android.os.extra.USER_ACCOUNT_OPTIONS";

    /** @hide */
    public static final int PIN_VERIFICATION_FAILED_INCORRECT = -3;
    /** @hide */
    public static final int PIN_VERIFICATION_FAILED_NOT_SET = -2;
    /** @hide */
    public static final int PIN_VERIFICATION_SUCCESS = -1;

    /**
     * Sent when user restrictions have changed.
     *
     * @hide
     */
    @SystemApi // To allow seeing it from CTS.
    public static final String ACTION_USER_RESTRICTIONS_CHANGED =
            "android.os.action.USER_RESTRICTIONS_CHANGED";

    /**
     * Error result indicating that this user is not allowed to add other users on this device.
     * This is a result code returned from the activity created by the intent
     * {@link #createUserCreationIntent(String, String, String, PersistableBundle)}.
     */
    public static final int USER_CREATION_FAILED_NOT_PERMITTED = Activity.RESULT_FIRST_USER;

    /**
     * Error result indicating that no more users can be created on this device.
     * This is a result code returned from the activity created by the intent
     * {@link #createUserCreationIntent(String, String, String, PersistableBundle)}.
     */
    public static final int USER_CREATION_FAILED_NO_MORE_USERS = Activity.RESULT_FIRST_USER + 1;

    /**
     * Indicates that users are switchable.
     * @hide
     */
    @SystemApi
    public static final int SWITCHABILITY_STATUS_OK = 0;

    /**
     * Indicated that the user is in a phone call.
     * @hide
     */
    @SystemApi
    public static final int SWITCHABILITY_STATUS_USER_IN_CALL = 1 << 0;

    /**
     * Indicates that user switching is disallowed ({@link #DISALLOW_USER_SWITCH} is set).
     * @hide
     */
    @SystemApi
    public static final int SWITCHABILITY_STATUS_USER_SWITCH_DISALLOWED = 1 << 1;

    /**
     * Indicates that the system user is locked and user switching is not allowed.
     * @hide
     */
    @SystemApi
    public static final int SWITCHABILITY_STATUS_SYSTEM_USER_LOCKED = 1 << 2;

    /**
     * Result returned in {@link #getUserSwitchability()} indicating user switchability.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "SWITCHABILITY_STATUS_" }, value = {
            SWITCHABILITY_STATUS_OK,
            SWITCHABILITY_STATUS_USER_IN_CALL,
            SWITCHABILITY_STATUS_USER_SWITCH_DISALLOWED,
            SWITCHABILITY_STATUS_SYSTEM_USER_LOCKED
    })
    public @interface UserSwitchabilityResult {}

    /**
     * A response code from {@link #removeUserWhenPossible(UserHandle, boolean)} indicating that
     * the specified user has been successfully removed.
     *
     * @hide
     */
    @SystemApi
    public static final int REMOVE_RESULT_REMOVED = 0;

    /**
     * A response code from {@link #removeUserWhenPossible(UserHandle, boolean)} indicating that
     * the specified user is marked so that it will be removed when the user is stopped or on boot.
     *
     * @hide
     */
    @SystemApi
    public static final int REMOVE_RESULT_DEFERRED = 1;

    /**
     * A response code from {@link #removeUserWhenPossible(UserHandle, boolean)} indicating that
     * the specified user is already in the process of being removed.
     *
     * @hide
     */
    @SystemApi
    public static final int REMOVE_RESULT_ALREADY_BEING_REMOVED = 2;

    /**
     * A response code indicating that the specified user is removable.
     *
     * @hide
     */
    public static final int REMOVE_RESULT_USER_IS_REMOVABLE = 3;

    /**
     * A response code from {@link #removeUserWhenPossible(UserHandle, boolean)} indicating that
     * an unknown error occurred that prevented the user from being removed or set as ephemeral.
     *
     * @hide
     */
    @SystemApi
    public static final int REMOVE_RESULT_ERROR_UNKNOWN = -1;

    /**
     * A response code from {@link #removeUserWhenPossible(UserHandle, boolean)} indicating that
     * the user could not be removed due to a {@link #DISALLOW_REMOVE_MANAGED_PROFILE} or
     * {@link #DISALLOW_REMOVE_USER} user restriction.
     *
     * @hide
     */
    @SystemApi
    public static final int REMOVE_RESULT_ERROR_USER_RESTRICTION = -2;

    /**
     * A response code from {@link #removeUserWhenPossible(UserHandle, boolean)} indicating that
     * user being removed does not exist.
     *
     * @hide
     */
    @SystemApi
    public static final int REMOVE_RESULT_ERROR_USER_NOT_FOUND = -3;

    /**
     * A response code from {@link #removeUserWhenPossible(UserHandle, boolean)} indicating that
     * user being removed is a {@link UserHandle#SYSTEM} user which can't be removed.
     *
     * @hide
     */
    @SystemApi
    public static final int REMOVE_RESULT_ERROR_SYSTEM_USER = -4;

    /**
     * A response code from {@link #removeUserWhenPossible(UserHandle, boolean)} indicating that
     * user being removed is a  {@link UserInfo#FLAG_MAIN}  user and can't be removed because
     * system property {@link com.android.internal.R.bool.isMainUserPermanentAdmin} is true.
     * @hide
     */
    @SystemApi
    public static final int REMOVE_RESULT_ERROR_MAIN_USER_PERMANENT_ADMIN = -5;

    /**
     * Possible response codes from {@link #removeUserWhenPossible(UserHandle, boolean)}.
     *
     * @hide
     */
    @IntDef(prefix = { "REMOVE_RESULT_" }, value = {
            REMOVE_RESULT_REMOVED,
            REMOVE_RESULT_DEFERRED,
            REMOVE_RESULT_ALREADY_BEING_REMOVED,
            REMOVE_RESULT_USER_IS_REMOVABLE,
            REMOVE_RESULT_ERROR_USER_RESTRICTION,
            REMOVE_RESULT_ERROR_USER_NOT_FOUND,
            REMOVE_RESULT_ERROR_SYSTEM_USER,
            REMOVE_RESULT_ERROR_MAIN_USER_PERMANENT_ADMIN,
            REMOVE_RESULT_ERROR_UNKNOWN,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RemoveResult {}

    /**
     * Indicates user operation is successful.
     */
    public static final int USER_OPERATION_SUCCESS = 0;

    /**
     * Indicates user operation failed for unknown reason.
     */
    public static final int USER_OPERATION_ERROR_UNKNOWN = 1;

    /**
     * Indicates user operation failed because target user is a managed profile.
     */
    public static final int USER_OPERATION_ERROR_MANAGED_PROFILE = 2;

    /**
     * Indicates user operation failed because maximum running user limit has been reached.
     */
    public static final int USER_OPERATION_ERROR_MAX_RUNNING_USERS = 3;

    /**
     * Indicates user operation failed because the target user is in the foreground.
     */
    public static final int USER_OPERATION_ERROR_CURRENT_USER = 4;

    /**
     * Indicates user operation failed because device has low data storage.
     */
    public static final int USER_OPERATION_ERROR_LOW_STORAGE = 5;

    /**
     * Indicates user operation failed because maximum user limit has been reached.
     */
    public static final int USER_OPERATION_ERROR_MAX_USERS = 6;

    /**
     * Indicates user operation failed because a user with that account already exists.
     *
     * @hide
     */
    @SystemApi
    public static final int USER_OPERATION_ERROR_USER_ACCOUNT_ALREADY_EXISTS = 7;

    /**
     * Result returned from various user operations.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "USER_OPERATION_" }, value = {
            USER_OPERATION_SUCCESS,
            USER_OPERATION_ERROR_UNKNOWN,
            USER_OPERATION_ERROR_MANAGED_PROFILE,
            USER_OPERATION_ERROR_MAX_RUNNING_USERS,
            USER_OPERATION_ERROR_CURRENT_USER,
            USER_OPERATION_ERROR_LOW_STORAGE,
            USER_OPERATION_ERROR_MAX_USERS,
            USER_OPERATION_ERROR_USER_ACCOUNT_ALREADY_EXISTS
    })
    public @interface UserOperationResult {}

    /**
     * Thrown to indicate user operation failed.
     */
    public static class UserOperationException extends RuntimeException {
        private final @UserOperationResult int mUserOperationResult;

        /**
         * Constructs a UserOperationException with specific result code.
         *
         * @param message the detail message
         * @param userOperationResult the result code
         * @hide
         */
        public UserOperationException(String message,
                @UserOperationResult int userOperationResult) {
            super(message);
            mUserOperationResult = userOperationResult;
        }

        /**
         * Returns the operation result code.
         */
        public @UserOperationResult int getUserOperationResult() {
            return mUserOperationResult;
        }

        /**
         * Returns a UserOperationException containing the same message and error code.
         * @hide
         */
        public static UserOperationException from(ServiceSpecificException exception) {
            return new UserOperationException(exception.getMessage(), exception.errorCode);
        }
    }

    /**
     * Converts the ServiceSpecificException into a UserOperationException or throws null;
     *
     * @param exception exception to convert.
     * @param throwInsteadOfNull if an exception should be thrown or null returned.
     * @return null if chosen not to throw exception.
     * @throws UserOperationException
     */
    private <T> T returnNullOrThrowUserOperationException(ServiceSpecificException exception,
            boolean throwInsteadOfNull) throws UserOperationException {
        if (throwInsteadOfNull) {
            throw UserOperationException.from(exception);
        } else {
            return null;
        }
    }

    /**
     * Thrown to indicate user operation failed. (Checked exception)
     * @hide
     */
    public static class CheckedUserOperationException extends AndroidException {
        private final @UserOperationResult int mUserOperationResult;

        /**
         * Constructs a CheckedUserOperationException with specific result code.
         *
         * @param message the detail message
         * @param userOperationResult the result code
         * @hide
         */
        public CheckedUserOperationException(String message,
                @UserOperationResult int userOperationResult) {
            super(message);
            mUserOperationResult = userOperationResult;
        }

        /** Returns the operation result code. */
        public @UserOperationResult int getUserOperationResult() {
            return mUserOperationResult;
        }

        /** Return a ServiceSpecificException containing the same message and error code. */
        public ServiceSpecificException toServiceSpecificException() {
            return new ServiceSpecificException(mUserOperationResult, getMessage());
        }
    }

    /**
     * For apps targeting {@link Build.VERSION_CODES#TIRAMISU} and above, any UserManager API marked
     * as {@link  android.annotation.UserHandleAware @UserHandleAware} will use the context user
     * (rather than the calling user).
     * For apps targeting an SDK version <em>below</em> this, the behaviour
     * depends on the particular method and when it was first introduced:
     * <ul>
     *     <li>
     *         if the {@literal @}UserHandleAware specifies a
     *         {@link  android.annotation.UserHandleAware#enabledSinceTargetSdkVersion} of
     *         {@link Build.VERSION_CODES#TIRAMISU} the <em>calling</em> user is used.
     *     </li>
     *     <li>
     *         if the {@literal @}UserHandleAware doesn't specify a
     *         {@link  android.annotation.UserHandleAware#enabledSinceTargetSdkVersion}, the
     *         <em>context</em> user is used.
     *     </li>
     *     <li>there should currently be no other values used by UserManager for
     *         {@link  android.annotation.UserHandleAware#enabledSinceTargetSdkVersion}, since all
     *         old implicitly user-dependant APIs were updated in that version and anything
     *         introduced more recently should already be {@literal @}UserHandleAware.
     *     </li>
     * </ul>
     *
     * Note that when an API marked with
     * {@link  android.annotation.UserHandleAware#enabledSinceTargetSdkVersion} is run
     * on a device whose OS predates that version, the calling user will be used, since on such a
     * device, the API is not {@literal @}UserHandleAware yet.
     *
     * @hide
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public static final long ALWAYS_USE_CONTEXT_USER = 183155436L;

    /**
     * Returns the context user or the calling user, depending on the target SDK.
     * New APIs do not require such gating and therefore should always use mUserId instead.
     * @see #ALWAYS_USE_CONTEXT_USER
     */
    private @UserIdInt int getContextUserIfAppropriate() {
        if (CompatChanges.isChangeEnabled(ALWAYS_USE_CONTEXT_USER)) {
            return mUserId;
        } else {
            final int callingUser = UserHandle.myUserId();
            if (callingUser != mUserId) {
                Log.w(TAG, "Using the calling user " + callingUser
                        + ", rather than the specified context user " + mUserId
                        + ", because API is only UserHandleAware on higher targetSdkVersions.",
                        new Throwable());
            }
            return callingUser;
        }
    }

    /** @hide */
    @UnsupportedAppUsage
    public static UserManager get(Context context) {
        return (UserManager) context.getSystemService(Context.USER_SERVICE);
    }

    /** @hide */
    public UserManager(Context context, IUserManager service) {
        mService = service;
        Context appContext = context.getApplicationContext();
        mContext = (appContext == null ? context : appContext);
        mUserId = context.getUserId();
    }

    /**
     * Returns whether this device supports multiple users with their own login and customizable
     * space.
     * @return whether the device supports multiple users.
     */
    public static boolean supportsMultipleUsers() {
        return getMaxSupportedUsers() > 1
                && SystemProperties.getBoolean("fw.show_multiuserui",
                Resources.getSystem().getBoolean(R.bool.config_enableMultiUserUI));
    }

    /**
     * @return Whether guest user is always ephemeral
     * @hide
     */
    public static boolean isGuestUserAlwaysEphemeral() {
        return Resources.getSystem()
                .getBoolean(com.android.internal.R.bool.config_guestUserEphemeral);
    }

    /**
     * @return true, when we want to enable user manager API and UX to allow
     *           guest user ephemeral state change based on user input
     * @hide
     */
    public static boolean isGuestUserAllowEphemeralStateChange() {
        return Resources.getSystem()
                .getBoolean(com.android.internal.R.bool.config_guestUserAllowEphemeralStateChange);
    }

    /**
     * Returns whether the device is configured to support a Communal Profile.
     * @hide
     */
    public static boolean isCommunalProfileEnabled() {
        return SystemProperties.getBoolean("persist.fw.omnipresent_communal_user",
                Resources.getSystem()
                        .getBoolean(com.android.internal.R.bool.config_omnipresentCommunalUser));
    }

    /**
     * Returns whether multiple admins are enabled on the device
     * @hide
     */
    public static boolean isMultipleAdminEnabled() {
        return Resources.getSystem()
                .getBoolean(com.android.internal.R.bool.config_enableMultipleAdmins);
    }

    /**
     * Checks whether the device is running in a headless system user mode.
     *
     * <p>Headless system user mode means the {@link #isSystemUser() system user} runs system
     * services and some system UI, but it is not associated with any real person and additional
     * users must be created to be associated with real persons.
     *
     * @return whether the device is running in a headless system user mode.
     */
    public static boolean isHeadlessSystemUserMode() {
        // No need for synchronization.  Once it becomes non-null, it'll be non-null forever.
        // (Its value is determined when UMS is constructed and cannot change.)
        // Worst case we might end up calling the AIDL method multiple times but that's fine.
        if (sIsHeadlessSystemUser == null) {
            // Unfortunately this API is static, but the property no longer is. So go fetch the UMS.
            try {
                final IUserManager service = IUserManager.Stub.asInterface(
                        ServiceManager.getService(Context.USER_SERVICE));
                sIsHeadlessSystemUser = service.isHeadlessSystemUserMode();
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return sIsHeadlessSystemUser;
    }

    /**
     * @deprecated use {@link #getUserSwitchability()} instead.
     *
     * @removed
     * @hide
     */
    @Deprecated
    @RequiresPermission(anyOf = {android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.INTERACT_ACROSS_USERS})
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @UserHandleAware
    public boolean canSwitchUsers() {
        try {
            return mService.getUserSwitchability(mUserId) == SWITCHABILITY_STATUS_OK;
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether switching users is currently allowed for the context user.
     * <p>
     * Switching users is not allowed in the following cases:
     * <li>the user is in a phone call</li>
     * <li>{@link #DISALLOW_USER_SWITCH} is set</li>
     * <li>system user hasn't been unlocked yet</li>
     *
     * @return A {@link UserSwitchabilityResult} flag indicating if the user is switchable.
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.INTERACT_ACROSS_USERS})
    @UserHandleAware(enabledSinceTargetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public @UserSwitchabilityResult int getUserSwitchability() {
        return getUserSwitchability(UserHandle.of(getContextUserIfAppropriate()));
    }

    /**
     * Returns whether switching users is currently allowed for the provided user.
     * <p>
     * Switching users is not allowed in the following cases:
     * <li>the user is in a phone call</li>
     * <li>{@link #DISALLOW_USER_SWITCH} is set</li>
     * <li>system user hasn't been unlocked yet</li>
     *
     * @return A {@link UserSwitchabilityResult} flag indicating if the user is switchable.
     * @hide
     */
    @RequiresPermission(anyOf = {android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.INTERACT_ACROSS_USERS})
    public @UserSwitchabilityResult int getUserSwitchability(UserHandle userHandle) {
        try {
            return mService.getUserSwitchability(userHandle.getIdentifier());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the userId for the context user.
     *
     * @return the userId of the context user.
     *
     * @deprecated To get the <em>calling</em> user, use {@link UserHandle#myUserId()}.
     *             To get the <em>context</em> user, get it directly from the context.
     *
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
    @UserHandleAware(enabledSinceTargetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    // *** Do NOT use this in UserManager. Instead always use mUserId. ***
    public @UserIdInt int getUserHandle() {
        return getContextUserIfAppropriate();
    }

    /**
     * Returns the userId for the user that this process is running under
     * (<em>not</em> the context user).
     *
     * @return the userId of <em>this process</em>.
     *
     * @deprecated Use {@link UserHandle#myUserId()}
     * @hide
     */
    @Deprecated
    // NOT @UserHandleAware
    public @UserIdInt int getProcessUserId() {
        return UserHandle.myUserId();
    }

    /**
     * @return the user type of the context user.
     * @hide
     */
    @TestApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.CREATE_USERS,
            android.Manifest.permission.QUERY_USERS})
    @UserHandleAware
    public @NonNull String getUserType() {
        UserInfo userInfo = getUserInfo(mUserId);
        return userInfo == null ? "" : userInfo.userType;
    }

    /**
     * Returns the user name of the context user. This call is only available to applications on
     * the system image.
     *
     * @return the user name
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.CREATE_USERS,
            android.Manifest.permission.QUERY_USERS,
            android.Manifest.permission.GET_ACCOUNTS_PRIVILEGED})

    @UserHandleAware(
            requiresAnyOfPermissionsIfNotCaller = {
                    android.Manifest.permission.MANAGE_USERS,
                    android.Manifest.permission.CREATE_USERS,
                    android.Manifest.permission.QUERY_USERS})
    public @NonNull String getUserName() {
        if (UserHandle.myUserId() == mUserId) {
            try {
                return mService.getUserName();
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        } else {
            UserInfo userInfo = getUserInfo(mUserId);
            if (userInfo != null && userInfo.name != null) {
                return userInfo.name;
            }
            return "";
        }
    }

    /**
     * Returns whether user name has been set.
     * <p>This method can be used to check that the value returned by {@link #getUserName()} was
     * set by the user and is not a placeholder string provided by the system.
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.CREATE_USERS,
            android.Manifest.permission.QUERY_USERS,
            android.Manifest.permission.GET_ACCOUNTS_PRIVILEGED})
    @UserHandleAware(
            enabledSinceTargetSdkVersion = Build.VERSION_CODES.TIRAMISU,
            requiresAnyOfPermissionsIfNotCaller = {
                    android.Manifest.permission.MANAGE_USERS,
                    android.Manifest.permission.CREATE_USERS,
                    android.Manifest.permission.QUERY_USERS})
    public boolean isUserNameSet() {
        try {
            return mService.isUserNameSet(getContextUserIfAppropriate());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Used to determine whether the user making this call is subject to
     * teleportations.
     *
     * <p>As of {@link android.os.Build.VERSION_CODES#LOLLIPOP}, this method can
     * now automatically identify goats using advanced goat recognition technology.</p>
     *
     * <p>As of {@link android.os.Build.VERSION_CODES#R}, this method always returns
     * {@code false} in order to protect goat privacy.</p>
     *
     * @return Returns whether the user making this call is a goat.
     */
    @UserHandleAware(enabledSinceTargetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public boolean isUserAGoat() {
        if (mContext.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.R) {
            return false;
        }
        // Caution: This is NOT @UserHandleAware (because mContext is getApplicationContext and
        // can hold a different userId), but for R+ it returns false, so it doesn't matter anyway.
        return mContext.getPackageManager()
                .isPackageAvailable("com.coffeestainstudios.goatsimulator");
    }

    /**
     * Used to check if the context user is the primary user. The primary user is the first human
     * user on a device. This is not supported in headless system user mode.
     *
     * @return whether the context user is the primary user.
     *
     * @deprecated This method always returns true for the system user, who may not be a full user
     * if {@link #isHeadlessSystemUserMode} is true. Use {@link #isSystemUser}, {@link #isAdminUser}
     * or {@link #isMainUser} instead.
     *
     * @hide
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(anyOf = {
            Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS,
            Manifest.permission.QUERY_USERS})
    @UserHandleAware(enabledSinceTargetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public boolean isPrimaryUser() {
        final UserInfo user = getUserInfo(getContextUserIfAppropriate());
        return user != null && user.isPrimary();
    }

    /**
     * Used to check if the context user is the system user. The system user
     * is the initial user that is implicitly created on first boot and hosts most of the
     * system services.
     *
     * @return whether the context user is the system user.
     */
    @UserHandleAware(enabledSinceTargetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public boolean isSystemUser() {
        return getContextUserIfAppropriate() == UserHandle.USER_SYSTEM;
    }

    /**
     * Returns {@code true} if the context user is the designated "main user" of the device. This
     * user may have access to certain features which are limited to at most one user. There will
     * never be more than one main user on a device.
     *
     * <p>Currently, on most form factors the first human user on the device will be the main user;
     * in the future, the concept may be transferable, so a different user (or even no user at all)
     * may be designated the main user instead. On other form factors there might not be a main
     * user.
     *
     * <p>Note that this will not be the system user on devices for which
     * {@link #isHeadlessSystemUserMode()} returns true.
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS,
            Manifest.permission.QUERY_USERS})
    @UserHandleAware
    public boolean isMainUser() {
        final UserInfo user = getUserInfo(mUserId);
        return user != null && user.isMain();
    }

    /**
     * Returns the designated "main user" of the device, or {@code null} if there is no main user.
     *
     * @see #isMainUser()
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS,
            Manifest.permission.QUERY_USERS})
    public @Nullable UserHandle getMainUser() {
        try {
            final int mainUserId = mService.getMainUserId();
            if (mainUserId == UserHandle.USER_NULL) {
                return null;
            }
            return UserHandle.of(mainUserId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }
    /**
     * Returns the designated "communal profile" of the device, or {@code null} if there is none.
     * @hide
     */
    @FlaggedApi(android.multiuser.Flags.FLAG_SUPPORT_COMMUNAL_PROFILE)
    @TestApi
    @RequiresPermission(anyOf = {
            Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS,
            Manifest.permission.QUERY_USERS})
    public @Nullable UserHandle getCommunalProfile() {
        try {
            final int userId = mService.getCommunalProfileId();
            if (userId == UserHandle.USER_NULL) {
                return null;
            }
            return UserHandle.of(userId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Checks if the context user is running in a communal profile.
     *
     * A communal profile is a {@link #isProfile() profile}, but instead of being associated with a
     * particular parent user, it is communal to the device.
     *
     * @return whether the context user is a communal profile.
     */
    @FlaggedApi(android.multiuser.Flags.FLAG_SUPPORT_COMMUNAL_PROFILE)
    @UserHandleAware(
            requiresAnyOfPermissionsIfNotCallerProfileGroup = {
                    android.Manifest.permission.MANAGE_USERS,
                    android.Manifest.permission.QUERY_USERS,
                    android.Manifest.permission.INTERACT_ACROSS_USERS})
    public boolean isCommunalProfile() {
        return isCommunalProfile(mUserId);
    }

    /**
     * Returns {@code true} if the given user is the designated "communal profile" of the device.
     * @hide
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.QUERY_USERS,
            android.Manifest.permission.INTERACT_ACROSS_USERS}, conditional = true)
    private boolean isCommunalProfile(@UserIdInt int userId) {
        return isUserTypeCommunalProfile(getProfileType(userId));
    }

    /**
     * Used to check if the context user is an admin user. An admin user may be allowed to
     * modify or configure certain settings that aren't available to non-admin users,
     * create and delete additional users, etc. There can be more than one admin users.
     *
     * @return whether the context user is an admin user.
     */
    @UserHandleAware(
            enabledSinceTargetSdkVersion = Build.VERSION_CODES.TIRAMISU,
            requiresAnyOfPermissionsIfNotCallerProfileGroup = {
                    Manifest.permission.MANAGE_USERS,
                    Manifest.permission.CREATE_USERS,
                    Manifest.permission.QUERY_USERS})
    public boolean isAdminUser() {
        try {
            return mService.isAdminUser(getContextUserIfAppropriate());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Returns whether the provided user is an admin user. There can be more than one admin
     * user.
     */
    @UnsupportedAppUsage
    @RequiresPermission(anyOf = {
            Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS,
            Manifest.permission.QUERY_USERS})
    public boolean isUserAdmin(@UserIdInt int userId) {
        UserInfo user = getUserInfo(userId);
        return user != null && user.isAdmin();
    }

    /**
     * Used to check if the user currently running in the <b>foreground</b> is an
     * {@link #isAdminUser() admin} user.
     *
     * @return whether the foreground user is an admin user.
     * @see #isAdminUser()
     * @see #isUserForeground()
     */
    @FlaggedApi(android.multiuser.Flags.FLAG_SUPPORT_COMMUNAL_PROFILE_NEXTGEN)
    public boolean isForegroundUserAdmin() {
        try {
            return mService.isForegroundUserAdmin();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the context user is of the given user type.
     *
     * @param userType the name of the user's user type, e.g.
     *                 {@link UserManager#USER_TYPE_PROFILE_MANAGED}.
     * @return true if the user is of the given user type.
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.CREATE_USERS,
            android.Manifest.permission.QUERY_USERS})
    @UserHandleAware
    public boolean isUserOfType(@NonNull String userType) {
        try {
            return mService.isUserOfType(mUserId, userType);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the user type is a
     * {@link UserManager#USER_TYPE_PROFILE_MANAGED managed profile}.
     * @hide
     */
    @android.ravenwood.annotation.RavenwoodKeep
    public static boolean isUserTypeManagedProfile(@Nullable String userType) {
        return USER_TYPE_PROFILE_MANAGED.equals(userType);
    }

    /**
     * Returns whether the user type is a {@link UserManager#USER_TYPE_FULL_GUEST guest user}.
     * @hide
     */
    @android.ravenwood.annotation.RavenwoodKeep
    public static boolean isUserTypeGuest(@Nullable String userType) {
        return USER_TYPE_FULL_GUEST.equals(userType);
    }

    /**
     * Returns whether the user type is a
     * {@link UserManager#USER_TYPE_FULL_RESTRICTED restricted user}.
     * @hide
     */
    @android.ravenwood.annotation.RavenwoodKeep
    public static boolean isUserTypeRestricted(@Nullable String userType) {
        return USER_TYPE_FULL_RESTRICTED.equals(userType);
    }

    /**
     * Returns whether the user type is a {@link UserManager#USER_TYPE_FULL_DEMO demo user}.
     * @hide
     */
    @android.ravenwood.annotation.RavenwoodKeep
    public static boolean isUserTypeDemo(@Nullable String userType) {
        return USER_TYPE_FULL_DEMO.equals(userType);
    }

    /**
     * Returns whether the user type is a {@link UserManager#USER_TYPE_PROFILE_CLONE clone user}.
     * @hide
     */
    @android.ravenwood.annotation.RavenwoodKeep
    public static boolean isUserTypeCloneProfile(@Nullable String userType) {
        return USER_TYPE_PROFILE_CLONE.equals(userType);
    }

    /**
     * Returns whether the user type is a
     * {@link UserManager#USER_TYPE_PROFILE_COMMUNAL communal profile}.
     * @hide
     */
    @android.ravenwood.annotation.RavenwoodKeep
    public static boolean isUserTypeCommunalProfile(@Nullable String userType) {
        return USER_TYPE_PROFILE_COMMUNAL.equals(userType);
    }

    /**
     * Returns whether the user type is a
     * {@link UserManager#USER_TYPE_PROFILE_PRIVATE private profile}.
     *
     * @hide
     */
    @android.ravenwood.annotation.RavenwoodKeep
    public static boolean isUserTypePrivateProfile(@Nullable String userType) {
        return USER_TYPE_PROFILE_PRIVATE.equals(userType);
    }

    /**
     * @hide
     * @deprecated Use {@link #isRestrictedProfile()}
     */
    @UnsupportedAppUsage
    @Deprecated
    @UserHandleAware(
            enabledSinceTargetSdkVersion = Build.VERSION_CODES.TIRAMISU,
            requiresAnyOfPermissionsIfNotCaller = {
                    android.Manifest.permission.MANAGE_USERS,
                    android.Manifest.permission.CREATE_USERS,
                    android.Manifest.permission.QUERY_USERS}
    )
    public boolean isLinkedUser() {
        return isRestrictedProfile();
    }

    /**
     * Used to check if the context user is a restricted profile. Restricted profiles
     * may have a reduced number of available apps, app restrictions, and account restrictions.
     *
     * <p>The caller must be in the same profile group as the context user or else hold
     * <li>{@link android.Manifest.permission#MANAGE_USERS},
     * <li>or {@link android.Manifest.permission#CREATE_USERS},
     * <li>or, for devices after {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE},
     * {@link android.Manifest.permission#QUERY_USERS}.
     *
     * @return whether the context user is a restricted profile.
     * @hide
     */
    @SystemApi
    @UserHandleAware(
            enabledSinceTargetSdkVersion = Build.VERSION_CODES.TIRAMISU,
            requiresAnyOfPermissionsIfNotCaller = {
                    android.Manifest.permission.MANAGE_USERS,
                    android.Manifest.permission.CREATE_USERS,
                    android.Manifest.permission.QUERY_USERS}
    )
    public boolean isRestrictedProfile() {
        try {
            return mService.isRestricted(getContextUserIfAppropriate());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Check if a user is a restricted profile. Restricted profiles may have a reduced number of
     * available apps, app restrictions, and account restrictions.
     *
     * <p>Requires
     * <li>{@link android.Manifest.permission#MANAGE_USERS},
     * <li>or {@link android.Manifest.permission#CREATE_USERS},
     * <li>or, for devices after {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE},
     * {@link android.Manifest.permission#QUERY_USERS}.
     *
     * @param user the user to check
     * @return whether the user is a restricted profile.
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS,
            Manifest.permission.QUERY_USERS},
            conditional = true)
    public boolean isRestrictedProfile(@NonNull UserHandle user) {
        try {
            return mService.isRestricted(user.getIdentifier());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Checks if the context user can have a restricted profile.
     * @return whether the context user can have a restricted profile.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    @UserHandleAware
    public boolean canHaveRestrictedProfile() {
        try {
            return mService.canHaveRestrictedProfile(mUserId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the context user has at least one restricted profile associated with it.
     * @return whether the user has a restricted profile associated with it
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    @UserHandleAware(enabledSinceTargetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public boolean hasRestrictedProfiles() {
        try {
            return mService.hasRestrictedProfiles(getContextUserIfAppropriate());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Get the parent of a restricted profile.
     *
     * @return the parent of the user or {@code null} if the user is not restricted profile
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS,
            Manifest.permission.QUERY_USERS})
    @UserHandleAware
    public @Nullable UserHandle getRestrictedProfileParent() {
        final UserInfo info = getUserInfo(mUserId);
        if (info == null) return null;
        if (!info.isRestricted()) return null;
        final int parent = info.restrictedProfileParentId;
        if (parent == UserHandle.USER_NULL) return null;
        return UserHandle.of(parent);
    }

    /**
     * Checks if a user is a guest user.
     * @return whether user is a guest user.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @RequiresPermission(anyOf = {
            Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS,
            Manifest.permission.QUERY_USERS})
    public boolean isGuestUser(@UserIdInt int userId) {
        UserInfo user = getUserInfo(userId);
        return user != null && user.isGuest();
    }

    /**
     * Used to check if the context user is a guest user. A guest user may be transient.
     *
     * @return whether the context user is a guest user.
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS,
            Manifest.permission.QUERY_USERS})
    @UserHandleAware(enabledSinceTargetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public boolean isGuestUser() {
        UserInfo user = getUserInfo(getContextUserIfAppropriate());
        return user != null && user.isGuest();
    }


    /**
     * Checks if the context user is a demo user. When running in a demo user,
     * apps can be more helpful to the user, or explain their features in more detail.
     *
     * @return whether the context user is a demo user.
     */
    @UserHandleAware(
            enabledSinceTargetSdkVersion = Build.VERSION_CODES.TIRAMISU,
            requiresPermissionIfNotCaller = android.Manifest.permission.MANAGE_USERS
    )
    public boolean isDemoUser() {
        try {
            return mService.isDemoUser(getContextUserIfAppropriate());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Checks if the context user is running in a profile. A profile is a user that
     * typically has its own separate data but shares its UI with some parent user. For example, a
     * {@link #isManagedProfile() managed profile} is a type of profile.
     *
     * @return whether the context user is in a profile.
     */
    @UserHandleAware(
            requiresAnyOfPermissionsIfNotCallerProfileGroup = {
                    android.Manifest.permission.MANAGE_USERS,
                    android.Manifest.permission.QUERY_USERS,
                    android.Manifest.permission.INTERACT_ACROSS_USERS})
    public boolean isProfile() {
        return isProfile(mUserId);
    }

    private boolean isProfile(@UserIdInt int userId) {
        final String profileType = getProfileType(userId);
        return profileType != null && !profileType.equals("");
    }

    /**
     * Returns the user type of the context user if it is a profile.
     *
     * This is a more specific form of {@link #getUserType()} with relaxed permission requirements.
     *
     * @return the user type of the context user if it is a {@link #isProfile() profile},
     *         an empty string if it is not a profile,
     *         or null if the user doesn't exist.
     */
    @UserHandleAware(
            requiresAnyOfPermissionsIfNotCallerProfileGroup = {
                    android.Manifest.permission.MANAGE_USERS,
                    android.Manifest.permission.QUERY_USERS,
                    android.Manifest.permission.INTERACT_ACROSS_USERS})
    private @Nullable String getProfileType() {
        return getProfileType(mUserId);
    }

    /** @see #getProfileType() */
    private @Nullable String getProfileType(@UserIdInt int userId) {
        // First, the typical case (i.e. the *process* user, not necessarily the context user).
        // This cache cannot be become invalidated since it's about the calling process itself.
        if (userId == UserHandle.myUserId()) {
            // No need for synchronization.  Once it becomes non-null, it'll be non-null forever.
            // Worst case we might end up calling the AIDL method multiple times but that's fine.
            if (mProfileTypeOfProcessUser != null) {
                return mProfileTypeOfProcessUser;
            }
            try {
                final String profileType = mService.getProfileType(userId);
                if (profileType != null) {
                    return mProfileTypeOfProcessUser = profileType.intern();
                }
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }

        // The userId is not for the process's user. Use a slower cache that handles invalidation.
        return mProfileTypeCache.query(userId);
    }

    /**
     * Checks if the context user is a managed profile.
     *
     * Note that this applies specifically to <em>managed</em> profiles. For profiles in general,
     * use {@link #isProfile()} instead.
     *
     * @return whether the context user is a managed profile.
     */
    @UserHandleAware(
            enabledSinceTargetSdkVersion = Build.VERSION_CODES.TIRAMISU,
            requiresAnyOfPermissionsIfNotCallerProfileGroup = {
                    android.Manifest.permission.MANAGE_USERS,
                    android.Manifest.permission.QUERY_USERS,
                    android.Manifest.permission.INTERACT_ACROSS_USERS})
    public boolean isManagedProfile() {
        return isManagedProfile(getContextUserIfAppropriate());
    }

    /**
     * Checks if the specified user is a managed profile.
     * Requires {@link android.Manifest.permission#MANAGE_USERS} or
     * {@link android.Manifest.permission#INTERACT_ACROSS_USERS} or
     * {@link android.Manifest.permission#QUERY_USERS} permission, otherwise the caller
     * must be in the same profile group of specified user.
     *
     * Note that this applies specifically to <em>managed</em> profiles. For profiles in general,
     * use {@link #isProfile()} instead.
     *
     * @return whether the specified user is a managed profile.
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.QUERY_USERS,
            android.Manifest.permission.INTERACT_ACROSS_USERS}, conditional = true)
    public boolean isManagedProfile(@UserIdInt int userId) {
        return isUserTypeManagedProfile(getProfileType(userId));
    }

    /**
     * Checks if the context user is a clone profile.
     *
     * @return whether the context user is a clone profile.
     *
     * @see android.os.UserManager#USER_TYPE_PROFILE_CLONE
     * @hide
     */
    @SystemApi
    @UserHandleAware(
            requiresAnyOfPermissionsIfNotCallerProfileGroup = {
                    android.Manifest.permission.MANAGE_USERS,
                    android.Manifest.permission.QUERY_USERS,
                    android.Manifest.permission.INTERACT_ACROSS_USERS})
    @SuppressAutoDoc
    public boolean isCloneProfile() {
        return isUserTypeCloneProfile(getProfileType());
    }

    /**
     * Checks if the context user is a private profile.
     *
     * <p>A Private profile is a separate {@link #isProfile() profile} that can be used to store
     * sensitive apps and data, which can be hidden or revealed at the user's discretion.
     *
     * @return whether the context user is a private profile.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(android.os.Flags.FLAG_ALLOW_PRIVATE_PROFILE)
    @UserHandleAware(
            requiresAnyOfPermissionsIfNotCallerProfileGroup = {
                    android.Manifest.permission.MANAGE_USERS,
                    android.Manifest.permission.QUERY_USERS,
                    android.Manifest.permission.INTERACT_ACROSS_USERS})
    @SuppressAutoDoc
    public boolean isPrivateProfile() {
        return isUserTypePrivateProfile(getProfileType());
    }

    /**
     * Checks if the context user is an ephemeral user.
     *
     * @return whether the context user is an ephemeral user.
     * @hide
     */
    @RequiresPermission(anyOf = {
            Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS,
            Manifest.permission.QUERY_USERS})
    @UserHandleAware
    public boolean isEphemeralUser() {
        return isUserEphemeral(mUserId);
    }

    /**
     * Returns whether the specified user is ephemeral.
     * @hide
     */
    @RequiresPermission(anyOf = {
            Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS,
            Manifest.permission.QUERY_USERS})
    public boolean isUserEphemeral(@UserIdInt int userId) {
        final UserInfo user = getUserInfo(userId);
        return user != null && user.isEphemeral();
    }

    /**
     * Return whether the given user is actively running.  This means that
     * the user is in the "started" state, not "stopped" -- it is currently
     * allowed to run code through scheduled alarms, receiving broadcasts,
     * etc.  A started user may be either the current foreground user or a
     * background user; the result here does not distinguish between the two.
     *
     * <p>Note prior to Android Nougat MR1 (SDK version <= 24;
     * {@link android.os.Build.VERSION_CODES#N}, this API required a system permission
     * in order to check other profile's status.
     * Since Android Nougat MR1 (SDK version >= 25;
     * {@link android.os.Build.VERSION_CODES#N_MR1}), the restriction has been relaxed, and now
     * it'll accept any {@link android.os.UserHandle} within the same profile group as the caller.
     *
     * @param user The user to retrieve the running state for.
     */
    @RequiresPermission(anyOf = {Manifest.permission.MANAGE_USERS,
            Manifest.permission.INTERACT_ACROSS_USERS}, conditional = true)
    public boolean isUserRunning(UserHandle user) {
        return isUserRunning(user.getIdentifier());
    }

    /** @hide */
    @RequiresPermission(anyOf = {Manifest.permission.MANAGE_USERS,
            Manifest.permission.INTERACT_ACROSS_USERS}, conditional = true)
    public boolean isUserRunning(@UserIdInt int userId) {
        try {
            return mService.isUserRunning(userId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Return whether the given user is actively running <em>or</em> stopping.
     * This is like {@link #isUserRunning(UserHandle)}, but will also return
     * true if the user had been running but is in the process of being stopped
     * (but is not yet fully stopped, and still running some code).
     *
     * <p>Note prior to Android Nougat MR1 (SDK version <= 24;
     * {@link android.os.Build.VERSION_CODES#N}, this API required a system permission
     * in order to check other profile's status.
     * Since Android Nougat MR1 (SDK version >= 25;
     * {@link android.os.Build.VERSION_CODES#N_MR1}), the restriction has been relaxed, and now
     * it'll accept any {@link android.os.UserHandle} within the same profile group as the caller.
     *
     * @param user The user to retrieve the running state for.
     */
    @RequiresPermission(anyOf = {Manifest.permission.MANAGE_USERS,
            Manifest.permission.INTERACT_ACROSS_USERS}, conditional = true)
    public boolean isUserRunningOrStopping(UserHandle user) {
        try {
            // TODO: reconcile stopped vs stopping?
            return ActivityManager.getService().isUserRunning(
                    user.getIdentifier(), ActivityManager.FLAG_OR_STOPPED);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Checks if the context user is running in the foreground.
     *
     * @return whether the context user is running in the foreground.
     */
    @UserHandleAware(
            requiresAnyOfPermissionsIfNotCaller = {
                    android.Manifest.permission.MANAGE_USERS,
                    android.Manifest.permission.INTERACT_ACROSS_USERS})
    public boolean isUserForeground() {
        try {
            return mService.isUserForeground(mUserId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @see #isVisibleBackgroundUsersSupported()
     * @hide
     */
    public static boolean isVisibleBackgroundUsersEnabled() {
        return SystemProperties.getBoolean("fw.visible_bg_users",
                Resources.getSystem()
                        .getBoolean(R.bool.config_multiuserVisibleBackgroundUsers));
    }

    /**
     * Returns whether the device allows full users to be started in background visible in a given
     * display (which would allow them to launch activities in that display).
     *
     * Note that this is specifically about allowing <b>full</b> users to be background visible.
     * Even if it is false, there can still be background visible users.
     *
     * In particular, the Communal Profile is a background visible user, and it can be supported
     * unrelated to the value of this method.
     *
     * @return {@code false} for most devices, except on automotive builds for vehicles with
     * passenger displays.
     *
     * @hide
     */
    // TODO(b/310249114): Rename to isVisibleBackgroundFullUsersSupported
    @TestApi
    public boolean isVisibleBackgroundUsersSupported() {
        return isVisibleBackgroundUsersEnabled();
    }

    /**
     * @hide
     */
    public static boolean isVisibleBackgroundUsersOnDefaultDisplayEnabled() {
        return SystemProperties.getBoolean("fw.visible_bg_users_on_default_display",
                Resources.getSystem()
                        .getBoolean(R.bool.config_multiuserVisibleBackgroundUsersOnDefaultDisplay));
    }

    /**
     * Returns whether the device allows full users to be started in background visible in the
     * {@link android.view.Display#DEFAULT_DISPLAY default display}.
     *
     * @return {@code false} for most devices, except passenger-only automotive build (i.e., when
     * Android runs in a separate system in the back seat to manage the passenger displays).
     *
     * @see #isVisibleBackgroundUsersSupported()
     * @hide
     */
    @TestApi
    public boolean isVisibleBackgroundUsersOnDefaultDisplaySupported() {
        return isVisibleBackgroundUsersOnDefaultDisplayEnabled();
    }

    /**
     * Checks if the user is visible at the moment.
     *
     * <p>Roughly speaking, a "visible user" is a user that can present UI on at least one display.
     * It includes:
     *
     * <ol>
     *   <li>The current foreground user.
     *   <li>(Running) profiles of the current foreground user.
     *   <li>Background users assigned to secondary displays (for example, passenger users on
     *   automotive builds, using the display associated with their seats).
     *   <li>A communal profile, if present.
     * </ol>
     *
     * @return whether the user is visible at the moment, as defined above.
     *
     * @hide
     */
    @SystemApi
    @UserHandleAware(
            requiresAnyOfPermissionsIfNotCaller = {
                    android.Manifest.permission.MANAGE_USERS,
                    android.Manifest.permission.INTERACT_ACROSS_USERS})
    public boolean isUserVisible() {
        try {
            return mService.isUserVisible(mUserId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the visible users (as defined by {@link #isUserVisible()}.
     *
     * @return visible users at the moment.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            "android.permission.INTERACT_ACROSS_USERS",
            "android.permission.MANAGE_USERS"
    })
    public @NonNull Set<UserHandle> getVisibleUsers() {
        ArraySet<UserHandle> result = new ArraySet<>();
        try {
            int[] visibleUserIds = mService.getVisibleUsers();
            if (visibleUserIds != null) {
                for (int userId : visibleUserIds) {
                    result.add(UserHandle.of(userId));
                }
            }
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
        return result;
    }

    /**
     * See {@link com.android.server.pm.UserManagerInternal#getMainDisplayAssignedToUser(int)}.
     *
     * @hide
     */
    @TestApi
    public int getMainDisplayIdAssignedToUser() {
        try {
            return mService.getMainDisplayIdAssignedToUser();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Return whether the context user is running in an "unlocked" state.
     * <p>
     * On devices with direct boot, a user is unlocked only after they've
     * entered their credentials (such as a lock pattern or PIN). On devices
     * without direct boot, a user is unlocked as soon as it starts.
     * <p>
     * When a user is locked, only device-protected data storage is available.
     * When a user is unlocked, both device-protected and credential-protected
     * private app data storage is available.
     *
     * @see Intent#ACTION_USER_UNLOCKED
     * @see Context#createDeviceProtectedStorageContext()
     */
    @UserHandleAware(
            enabledSinceTargetSdkVersion = Build.VERSION_CODES.TIRAMISU,
            requiresAnyOfPermissionsIfNotCallerProfileGroup = {
                    android.Manifest.permission.MANAGE_USERS,
                    android.Manifest.permission.INTERACT_ACROSS_USERS}
    )
    public boolean isUserUnlocked() {
        return isUserUnlocked(getContextUserIfAppropriate());
    }

    /**
     * Return whether the given user is running in an "unlocked" state.
     * <p>
     * On devices with direct boot, a user is unlocked only after they've
     * entered their credentials (such as a lock pattern or PIN). On devices
     * without direct boot, a user is unlocked as soon as it starts.
     * <p>
     * When a user is locked, only device-protected data storage is available.
     * When a user is unlocked, both device-protected and credential-protected
     * private app data storage is available.
     * <p>Requires {@code android.permission.MANAGE_USERS} or
     * {@code android.permission.INTERACT_ACROSS_USERS}, otherwise specified {@link UserHandle user}
     * must be the calling user or a profile associated with it.
     *
     * @param user to retrieve the unlocked state for.
     * @see Intent#ACTION_USER_UNLOCKED
     * @see Context#createDeviceProtectedStorageContext()
     */
    @RequiresPermission(anyOf = {Manifest.permission.MANAGE_USERS,
            Manifest.permission.INTERACT_ACROSS_USERS}, conditional = true)
    public boolean isUserUnlocked(UserHandle user) {
        return isUserUnlocked(user.getIdentifier());
    }

    private static final String CACHE_KEY_IS_USER_UNLOCKED_PROPERTY =
            "cache_key.is_user_unlocked";

    private final PropertyInvalidatedCache<Integer, Boolean> mIsUserUnlockedCache =
            new PropertyInvalidatedCache<Integer, Boolean>(
                32, CACHE_KEY_IS_USER_UNLOCKED_PROPERTY) {
                @Override
                public Boolean recompute(Integer query) {
                    try {
                        return mService.isUserUnlocked(query);
                    } catch (RemoteException re) {
                        throw re.rethrowFromSystemServer();
                    }
                }
                @Override
                public boolean bypass(Integer query) {
                    return query < 0;
                }
            };

    // Uses IS_USER_UNLOCKED_PROPERTY for invalidation as the APIs have the same dependencies.
    private final PropertyInvalidatedCache<Integer, Boolean> mIsUserUnlockingOrUnlockedCache =
            new PropertyInvalidatedCache<Integer, Boolean>(
                32, CACHE_KEY_IS_USER_UNLOCKED_PROPERTY) {
                @Override
                public Boolean recompute(Integer query) {
                    try {
                        return mService.isUserUnlockingOrUnlocked(query);
                    } catch (RemoteException re) {
                        throw re.rethrowFromSystemServer();
                    }
                }
                @Override
                public boolean bypass(Integer query) {
                    return query < 0;
                }
            };

    /** @hide */
    @UnsupportedAppUsage
    @RequiresPermission(anyOf = {Manifest.permission.MANAGE_USERS,
            Manifest.permission.INTERACT_ACROSS_USERS}, conditional = true)
    public boolean isUserUnlocked(@UserIdInt int userId) {
        return mIsUserUnlockedCache.query(userId);
    }

    /** @hide */
    public void disableIsUserUnlockedCache() {
        mIsUserUnlockedCache.disableLocal();
        mIsUserUnlockingOrUnlockedCache.disableLocal();
    }

    /** @hide */
    public static final void invalidateIsUserUnlockedCache() {
        PropertyInvalidatedCache.invalidateCache(CACHE_KEY_IS_USER_UNLOCKED_PROPERTY);
    }

    /**
     * Return whether the provided user is already running in an
     * "unlocked" state or in the process of unlocking.
     * <p>
     * On devices with direct boot, a user is unlocked only after they've
     * entered their credentials (such as a lock pattern or PIN). On devices
     * without direct boot, a user is unlocked as soon as it starts.
     * <p>
     * When a user is locked, only device-protected data storage is available.
     * When a user is unlocked, both device-protected and credential-protected
     * private app data storage is available.
     *
     * <p>Requires {@code android.permission.MANAGE_USERS} or
     * {@code android.permission.INTERACT_ACROSS_USERS}, otherwise specified {@link UserHandle user}
     * must be the calling user or a profile associated with it.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {Manifest.permission.MANAGE_USERS,
            Manifest.permission.INTERACT_ACROSS_USERS}, conditional = true)
    public boolean isUserUnlockingOrUnlocked(@NonNull UserHandle user) {
        return isUserUnlockingOrUnlocked(user.getIdentifier());
    }

    /** @hide */
    @RequiresPermission(anyOf = {Manifest.permission.MANAGE_USERS,
            Manifest.permission.INTERACT_ACROSS_USERS}, conditional = true)
    public boolean isUserUnlockingOrUnlocked(@UserIdInt int userId) {
        return mIsUserUnlockingOrUnlockedCache.query(userId);
    }

    /**
     * Return the time when the calling user started in elapsed milliseconds since boot,
     * or 0 if not started.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    // NOT @UserHandleAware
    public long getUserStartRealtime() {
        if (getContextUserIfAppropriate() != UserHandle.myUserId()) {
            // Note: If we want to support this in the future, also annotate with
            //       @UserHandleAware(enabledSinceTargetSdkVersion = Build.VERSION_CODES.TIRAMISU)
            throw new IllegalArgumentException("Calling from a context differing from the calling "
                    + "user is not currently supported.");
        }
        try {
            return mService.getUserStartRealtime();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Return the time when the context user was unlocked elapsed milliseconds since boot,
     * or 0 if not unlocked.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    // NOT @UserHandleAware
    public long getUserUnlockRealtime() {
        if (getContextUserIfAppropriate() != UserHandle.myUserId()) {
            // Note: If we want to support this in the future, also annotate with
            //       @UserHandleAware(enabledSinceTargetSdkVersion = Build.VERSION_CODES.TIRAMISU)
            throw new IllegalArgumentException("Calling from a context differing from the calling "
                    + "user is not currently supported.");
        }
        try {
            return mService.getUserUnlockRealtime();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the UserInfo object describing a specific user.
     * @param userId the user handle of the user whose information is being requested.
     * @return the UserInfo object for a specific user.
     * @hide
     */
    @UnsupportedAppUsage
    @RequiresPermission(anyOf = {
            Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS,
            Manifest.permission.QUERY_USERS})
    public UserInfo getUserInfo(@UserIdInt int userId) {
        try {
            return mService.getUserInfo(userId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a {@link UserProperties} object describing the properties of the given user.
     *
     * Note that the caller may not have permission to access all items; requesting any item for
     * which permission is lacking will throw a {@link SecurityException}.
     *
     * <p> Requires
     * {@code android.Manifest.permission#MANAGE_USERS},
     * {@code android.Manifest.permission#QUERY_USERS}, or
     * {@code android.Manifest.permission#INTERACT_ACROSS_USERS}
     * permission, or else the caller must be in the same profile group as the caller.
     *
     * @param userHandle the user handle of the user whose information is being requested.
     * @return a UserProperties object for a specific user.
     * @throws IllegalArgumentException if {@code userHandle} doesn't correspond to an existing user
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.QUERY_USERS,
            android.Manifest.permission.INTERACT_ACROSS_USERS}, conditional = true)
    public @NonNull UserProperties getUserProperties(@NonNull UserHandle userHandle) {
        return mUserPropertiesCache.query(userHandle.getIdentifier());
    }

    /**
     * @hide
     *
     * Returns who set a user restriction on a user.
     * @param restrictionKey the string key representing the restriction
     * @param userHandle the UserHandle of the user for whom to retrieve the restrictions.
     * @return The source of user restriction. Any combination of {@link #RESTRICTION_NOT_SET},
     *         {@link #RESTRICTION_SOURCE_SYSTEM}, {@link #RESTRICTION_SOURCE_DEVICE_OWNER}
     *         and {@link #RESTRICTION_SOURCE_PROFILE_OWNER}
     * @deprecated use {@link #getUserRestrictionSources(String, int)} instead.
     */
    @Deprecated
    @SystemApi
    @UserRestrictionSource
    @RequiresPermission(anyOf = {
            Manifest.permission.MANAGE_USERS,
            Manifest.permission.QUERY_USERS})
    public int getUserRestrictionSource(@UserRestrictionKey String restrictionKey,
            UserHandle userHandle) {
        try {
            return mService.getUserRestrictionSource(restrictionKey, userHandle.getIdentifier());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     *
     * Returns a list of users who set a user restriction on a given user.
     * @param restrictionKey the string key representing the restriction
     * @param userHandle the UserHandle of the user for whom to retrieve the restrictions.
     * @return a list of user ids enforcing this restriction.
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.QUERY_USERS})
    public List<EnforcingUser> getUserRestrictionSources(
            @UserRestrictionKey String restrictionKey, UserHandle userHandle) {
        try {
            return mService.getUserRestrictionSources(restrictionKey, userHandle.getIdentifier());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the user-wide restrictions imposed on the context user.
     * @return a Bundle containing all the restrictions.
     */
    @UserHandleAware(
            enabledSinceTargetSdkVersion = Build.VERSION_CODES.TIRAMISU,
            requiresAnyOfPermissionsIfNotCallerProfileGroup = {
                    android.Manifest.permission.MANAGE_USERS,
                    android.Manifest.permission.INTERACT_ACROSS_USERS}
    )
    public Bundle getUserRestrictions() {
        try {
            return mService.getUserRestrictions(getContextUserIfAppropriate());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the user-wide restrictions imposed on the user specified by <code>userHandle</code>.
     * @param userHandle the UserHandle of the user for whom to retrieve the restrictions.
     * @return a Bundle containing all the restrictions.
     *
     * <p>Requires {@code android.permission.MANAGE_USERS} or
     * {@code android.permission.INTERACT_ACROSS_USERS}, otherwise specified {@link UserHandle user}
     * must be the calling user or a profile associated with it.
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.INTERACT_ACROSS_USERS}, conditional = true)
    public Bundle getUserRestrictions(UserHandle userHandle) {
        try {
            return mService.getUserRestrictions(userHandle.getIdentifier());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

     /**
     * @hide
     * Returns whether the given user has been disallowed from performing certain actions
     * or setting certain settings through UserManager (e.g. this type of restriction would prevent
     * the guest user from doing certain things, such as making calls). This method disregards
     * restrictions set by device policy.
     * @param restrictionKey the string key representing the restriction
     * @param userHandle the UserHandle of the user for whom to retrieve the restrictions.
     */
    @TestApi
    @UnsupportedAppUsage
    @RequiresPermission(anyOf = {Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS})
    public boolean hasBaseUserRestriction(@UserRestrictionKey @NonNull String restrictionKey,
            @NonNull UserHandle userHandle) {
        try {
            return mService.hasBaseUserRestriction(restrictionKey, userHandle.getIdentifier());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * This will no longer work.  Device owners and profile owners should use
     * {@link DevicePolicyManager#addUserRestriction(ComponentName, String)} instead.
     */
    // System apps should use UserManager.setUserRestriction() instead.
    @Deprecated
    public void setUserRestrictions(Bundle restrictions) {
        throw new UnsupportedOperationException("This method is no longer supported");
    }

    /**
     * This will no longer work.  Device owners and profile owners should use
     * {@link DevicePolicyManager#addUserRestriction(ComponentName, String)} instead.
     */
    // System apps should use UserManager.setUserRestriction() instead.
    @Deprecated
    public void setUserRestrictions(Bundle restrictions, UserHandle userHandle) {
        throw new UnsupportedOperationException("This method is no longer supported");
    }

    /**
     * Sets the value of a specific restriction on the context user.
     * Requires the MANAGE_USERS permission.
     * @param key the key of the restriction
     * @param value the value for the restriction
     * @deprecated use {@link android.app.admin.DevicePolicyManager#addUserRestriction(
     * android.content.ComponentName, String)} or
     * {@link android.app.admin.DevicePolicyManager#clearUserRestriction(
     * android.content.ComponentName, String)} instead.
     */
    @Deprecated
    @RequiresPermission(Manifest.permission.MANAGE_USERS)
    @UserHandleAware(enabledSinceTargetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public void setUserRestriction(String key, boolean value) {
        try {
            mService.setUserRestriction(key, value, getContextUserIfAppropriate());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Sets the value of a specific restriction on a specific user.
     * @param key the key of the restriction
     * @param value the value for the restriction
     * @param userHandle the user whose restriction is to be changed.
     * @deprecated use {@link android.app.admin.DevicePolicyManager#addUserRestriction(
     * android.content.ComponentName, String)} or
     * {@link android.app.admin.DevicePolicyManager#clearUserRestriction(
     * android.content.ComponentName, String)} instead.
     */
    @Deprecated
    @RequiresPermission(Manifest.permission.MANAGE_USERS)
    public void setUserRestriction(String key, boolean value, UserHandle userHandle) {
        try {
            mService.setUserRestriction(key, value, userHandle.getIdentifier());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the context user has been disallowed from performing certain actions
     * or setting certain settings.
     *
     * @param restrictionKey The string key representing the restriction.
     * @return {@code true} if the context user has the given restriction, {@code false} otherwise.
     */
    @UserHandleAware(
            enabledSinceTargetSdkVersion = Build.VERSION_CODES.TIRAMISU,
            requiresAnyOfPermissionsIfNotCallerProfileGroup = {
                    android.Manifest.permission.MANAGE_USERS,
                    android.Manifest.permission.INTERACT_ACROSS_USERS}
    )
    public boolean hasUserRestriction(@UserRestrictionKey String restrictionKey) {
        return hasUserRestrictionForUser(restrictionKey, getContextUserIfAppropriate());
    }

    /**
     * @hide
     * Returns whether the given user has been disallowed from performing certain actions
     * or setting certain settings.
     * @param restrictionKey the string key representing the restriction
     * @param userHandle the UserHandle of the user for whom to retrieve the restrictions.
     * @deprecated Use {@link #hasUserRestrictionForUser(String, UserHandle)} instead.
     */
    @UnsupportedAppUsage
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.INTERACT_ACROSS_USERS}, conditional = true)
    @Deprecated
    public boolean hasUserRestriction(@UserRestrictionKey String restrictionKey,
            UserHandle userHandle) {
        return hasUserRestrictionForUser(restrictionKey, userHandle);
    }

    /**
     * Returns whether the given user has been disallowed from performing certain actions
     * or setting certain settings.
     * @param restrictionKey the string key representing the restriction
     * @param userHandle the UserHandle of the user for whom to retrieve the restrictions.
     *
     * <p>Requires {@code android.permission.MANAGE_USERS} or
     * {@code android.permission.INTERACT_ACROSS_USERS}, otherwise specified {@link UserHandle user}
     * must be the calling user or a profile associated with it.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.INTERACT_ACROSS_USERS}, conditional = true)
    public boolean hasUserRestrictionForUser(@NonNull @UserRestrictionKey String restrictionKey,
            @NonNull UserHandle userHandle) {
        return hasUserRestrictionForUser(restrictionKey, userHandle.getIdentifier());
    }

    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.INTERACT_ACROSS_USERS}, conditional = true)
    private boolean hasUserRestrictionForUser(@NonNull @UserRestrictionKey String restrictionKey,
            @UserIdInt int userId) {
        try {
            return mService.hasUserRestriction(restrictionKey, userId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Returns whether any user on the device has the given user restriction set.
     */
    public boolean hasUserRestrictionOnAnyUser(@UserRestrictionKey String restrictionKey) {
        try {
            return mService.hasUserRestrictionOnAnyUser(restrictionKey);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     *
     * Checks whether changing the given setting to the given value is prohibited
     * by the corresponding user restriction in the given user.
     *
     * May only be called by the OS itself.
     *
     * @return {@code true} if the change is prohibited, {@code false} if the change is allowed.
     */
    public boolean isSettingRestrictedForUser(String setting, @UserIdInt int userId,
            String value, int callingUid) {
        try {
            return mService.isSettingRestrictedForUser(setting, userId, value, callingUid);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Register a binder callback for user restrictions changes.
     * May only be called by the OS itself.
     */
    public void addUserRestrictionsListener(final IUserRestrictionsListener listener) {
        try {
            mService.addUserRestrictionsListener(listener);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
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
        int ident = getUserHandle((int) serialNumber);
        return ident >= 0 ? new UserHandle(ident) : null;
    }

    /**
     * Creates a user with the specified name and options.
     * Default user restrictions will be applied.
     * Requires {@link android.Manifest.permission#MANAGE_USERS} permission.
     *
     * @param name the user's name
     * @param flags UserInfo flags that identify the type of user and other properties.
     * @see UserInfo
     *
     * @return the UserInfo object for the created user, or null if the user could not be created.
     * @throws IllegalArgumentException if flags do not correspond to a valid user type.
     * @deprecated Use {@link #createUser(String, String, int)} instead.
     * @hide
     */
    @UnsupportedAppUsage
    @Deprecated
    public @Nullable UserInfo createUser(@Nullable String name, @UserInfoFlag int flags) {
        return createUser(name, UserInfo.getDefaultUserType(flags), flags);
    }

    /**
     * Creates a user with the specified name and options.
     * Default user restrictions will be applied.
     *
     * <p>Requires {@link android.Manifest.permission#MANAGE_USERS}.
     * {@link android.Manifest.permission#CREATE_USERS} suffices if flags are in
     * com.android.server.pm.UserManagerService#ALLOWED_FLAGS_FOR_CREATE_USERS_PERMISSION.
     *
     * @param name     the user's name
     * @param userType the type of user, such as {@link UserManager#USER_TYPE_FULL_GUEST}.
     * @param flags    UserInfo flags that specify user properties.
     * @return the {@link UserInfo} object for the created user, or {@code null} if the user
     *         could not be created.
     *
     * @see UserInfo
     *
     * @hide
     */
    @RequiresPermission(anyOf = {Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS})
    @TestApi
    public @Nullable UserInfo createUser(@Nullable String name, @NonNull String userType,
            @UserInfoFlag int flags) {
        try {
            return mService.createUserWithThrow(name, userType, flags);
        } catch (ServiceSpecificException e) {
            return null;
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a user with the specified {@link NewUserRequest}.
     *
     * @param newUserRequest specify the user information
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS})
    public @NonNull NewUserResponse createUser(@NonNull NewUserRequest newUserRequest) {
        try {
            final UserHandle userHandle = mService.createUserWithAttributes(
                    newUserRequest.getName(),
                    newUserRequest.getUserType(),
                    newUserRequest.getFlags(),
                    newUserRequest.getUserIcon(),
                    newUserRequest.getAccountName(),
                    newUserRequest.getAccountType(),
                    newUserRequest.getAccountOptions());

            return new NewUserResponse(userHandle, USER_OPERATION_SUCCESS);

        } catch (ServiceSpecificException e) {
            Log.w(TAG, "Exception while creating user " + newUserRequest, e);
            return new NewUserResponse(null, e.errorCode);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Pre-creates a user of the specified type. Default user restrictions will be applied.
     *
     * <p>This method can be used by OEMs to "warm" up the user creation by pre-creating some users
     * at the first boot, so they when the "real" user is created (for example,
     * by {@link #createUser(String, String, int)} or {@link #createGuest(Context)}), it
     * takes less time.
     *
     * <p>This method completes the majority of work necessary for user creation: it
     * creates user data, CE and DE encryption keys, app data directories, initializes the user and
     * grants default permissions. When pre-created users become "real" users, only then are
     * components notified of new user creation by firing user creation broadcasts.
     *
     * <p>All pre-created users are removed during system upgrade.
     *
     * <p>Requires {@link android.Manifest.permission#MANAGE_USERS}.
     * {@link android.Manifest.permission#CREATE_USERS} suffices if flags are in
     * com.android.server.pm.UserManagerService#ALLOWED_FLAGS_FOR_CREATE_USERS_PERMISSION.
     *
     *
     * @param userType the type of user, such as {@link UserManager#USER_TYPE_FULL_GUEST}.
     * @return the {@link UserInfo} object for the created user.
     *
     * @throws UserOperationException if the user could not be created.
     *
     * @deprecated Pre-created users are deprecated. This method should no longer be used, and will
     *             be removed once all the callers are removed.
     *
     * @hide
     */
    @Deprecated
    @TestApi
    @RequiresPermission(anyOf = {Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS})
    public @NonNull UserInfo preCreateUser(@NonNull String userType)
            throws UserOperationException {
        Log.w(TAG, "preCreateUser(): Pre-created user is deprecated.");
        try {
            return mService.preCreateUserWithThrow(userType);
        } catch (ServiceSpecificException e) {
            throw UserOperationException.from(e);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a guest user and configures it.
     * @param context an application context
     * @return the {@link UserInfo} object for the created user, or {@code null} if the user
     *         could not be created.
     *
     * @hide
     */
    @RequiresPermission(anyOf = {Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS})
    public @Nullable UserInfo createGuest(Context context) {
        try {
            final UserInfo guest = mService.createUserWithThrow(null, USER_TYPE_FULL_GUEST, 0);
            Settings.Secure.putStringForUser(context.getContentResolver(),
                    Settings.Secure.SKIP_FIRST_USE_HINTS, "1", guest.id);

            if (UserManager.isGuestUserAllowEphemeralStateChange()) {
                // Mark guest as (changeably) ephemeral if REMOVE_GUEST_ON_EXIT is 1
                // This is done so that a user via a UI controller can choose to
                // make a guest as ephemeral or not.
                // Settings.Global.REMOVE_GUEST_ON_EXIT holds the choice on what the guest state
                // should be, with default being ephemeral.
                boolean resetGuestOnExit = Settings.Global.getInt(context.getContentResolver(),
                                             Settings.Global.REMOVE_GUEST_ON_EXIT, 1) == 1;

                if (resetGuestOnExit && !guest.isEphemeral()) {
                    setUserEphemeral(guest.id, true);
                }
            }
            return guest;
        } catch (ServiceSpecificException e) {
            return null;
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    // TODO(b/256690588): Remove this after removing its callsites.
    /**
     * Gets the existing guest user if it exists.  This does not include guest users that are dying.
     * @return The existing guest user if it exists. Null otherwise.
     * @hide
     *
     * @deprecated Use {@link #getGuestUsers()}
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public UserInfo findCurrentGuestUser() {
        try {
            final List<UserInfo> guestUsers = mService.getGuestUsers();
            if (guestUsers.size() == 0) {
                return null;
            }
            return guestUsers.get(0);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the existing guest users.  This does not include guest users that are dying.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public @NonNull List<UserInfo> getGuestUsers() {
        try {
            return mService.getGuestUsers();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a user with the specified name and options as a profile of the context's user.
     *
     * @param name the user's name.
     * @param userType the type of user, such as {@link UserManager#USER_TYPE_PROFILE_MANAGED}.
     * @param disallowedPackages packages to not install for this profile.
     *
     * @return the {@link android.os.UserHandle} object for the created user,
     *         or throws {@link UserOperationException} if the user could not be created
     *         and calling app is targeting {@link android.os.Build.VERSION_CODES#R} or above
     *         (otherwise returns {@code null}).
     *
     * @throws UserOperationException if the user could not be created and the calling app is
     *         targeting {@link android.os.Build.VERSION_CODES#R} or above.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS})
    @UserHandleAware
    public @Nullable UserHandle createProfile(@NonNull String name, @NonNull String userType,
            @NonNull Set<String> disallowedPackages) throws UserOperationException {
        try {
            return mService.createProfileForUserWithThrow(name, userType, 0,
                    mUserId, disallowedPackages.toArray(
                            new String[disallowedPackages.size()])).getUserHandle();
        } catch (ServiceSpecificException e) {
            return returnNullOrThrowUserOperationException(e,
                    mContext.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.R);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a user with the specified name and options as a profile of another user.
     * <p>Requires MANAGE_USERS. CREATE_USERS suffices for ALLOWED_FLAGS_FOR_CREATE_USERS_PERMISSION
     *
     * @param name the user's name
     * @param flags flags that identify the type of user and other properties.
     * @param userId new user will be a profile of this user.
     *
     * @return the {@link UserInfo} object for the created user, or null if the user
     *         could not be created.
     * @throws IllegalArgumentException if flags do not correspond to a valid user type.
     * @deprecated Use {@link #createProfileForUser(String, String, int, int)} instead.
     * @hide
     */
    @UnsupportedAppUsage
    @RequiresPermission(anyOf = {Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS})
    @Deprecated
    public UserInfo createProfileForUser(String name, @UserInfoFlag int flags,
            @UserIdInt int userId) {
        return createProfileForUser(name, UserInfo.getDefaultUserType(flags), flags,
                userId, null);
    }

    /**
     * Creates a user with the specified name and options as a profile of another user.
     *
     * @param name the user's name
     * @param userType the type of user, such as {@link UserManager#USER_TYPE_PROFILE_MANAGED}.
     * @param flags UserInfo flags that specify user properties.
     * @param userId new user will be a profile of this user.
     *
     * @return the {@link UserInfo} object for the created user, or null if the user
     *         could not be created.
     * @hide
     */
    @RequiresPermission(anyOf = {Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS})
    public @Nullable UserInfo createProfileForUser(String name, @NonNull String userType,
            @UserInfoFlag int flags, @UserIdInt int userId) {
        return createProfileForUser(name, userType, flags, userId, null);
    }

    /**
     * Version of {@link #createProfileForUser(String, String, int, int)} that allows you to specify
     * any packages that should not be installed in the new profile by default, these packages can
     * still be installed later by the user if needed.
     *
     * @param name the user's name
     * @param userType the type of user, such as {@link UserManager#USER_TYPE_PROFILE_MANAGED}.
     * @param flags UserInfo flags that specify user properties.
     * @param userId new user will be a profile of this user.
     * @param disallowedPackages packages that will not be installed in the profile being created.
     *
     * @return the {@link UserInfo} object for the created user, or {@code null} if the user could
     *         not be created.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(anyOf = {Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS})
    public @Nullable UserInfo createProfileForUser(@Nullable String name, @NonNull String userType,
            @UserInfoFlag int flags, @UserIdInt int userId, @Nullable String[] disallowedPackages) {
        try {
            return mService.createProfileForUserWithThrow(name, userType, flags, userId,
                    disallowedPackages);
        } catch (ServiceSpecificException e) {
            return null;
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Similar to {@link #createProfileForUser(String, String, int, int, String[])}
     * except bypassing the checking of {@link UserManager#DISALLOW_ADD_MANAGED_PROFILE}.
     *
     * @see #createProfileForUser(String, String, int, int, String[])
     * @hide
     */
    @RequiresPermission(anyOf = {Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS})
    public @Nullable UserInfo createProfileForUserEvenWhenDisallowed(String name,
            @NonNull String userType, @UserInfoFlag int flags, @UserIdInt int userId,
            String[] disallowedPackages) {
        try {
            return mService.createProfileForUserEvenWhenDisallowedWithThrow(name, userType, flags,
                    userId, disallowedPackages);
        } catch (ServiceSpecificException e) {
            return null;
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a restricted profile with the specified name. This method also sets necessary
     * restrictions and adds shared accounts (with the context user).
     *
     * @param name profile's name
     * @return the {@link UserInfo} object for the created user, or {@code null} if the user
     *         could not be created.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(anyOf = {Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS})
    @UserHandleAware
    public @Nullable UserInfo createRestrictedProfile(@Nullable String name) {
        try {
            final int parentUserId = mUserId;
            final UserInfo profile = mService.createRestrictedProfileWithThrow(name, parentUserId);
            final UserHandle parentUserHandle = UserHandle.of(parentUserId);
            AccountManager.get(mContext).addSharedAccountsFromParentUser(parentUserHandle,
                    UserHandle.of(profile.id));
            return profile;
        } catch (ServiceSpecificException e) {
            return null;
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns an intent to create a user for the provided name and account name. The name
     * and account name will be used when the setup process for the new user is started.
     * <p>
     * The intent should be launched using startActivityForResult and the return result will
     * indicate if the user consented to adding a new user and if the operation succeeded. Any
     * errors in creating the user will be returned in the result code. If the user cancels the
     * request, the return result will be {@link Activity#RESULT_CANCELED}. On success, the
     * result code will be {@link Activity#RESULT_OK}.
     * <p>
     * Use {@link #supportsMultipleUsers()} to first check if the device supports this operation
     * at all.
     * <p>
     * The new user is created but not initialized. After switching into the user for the first
     * time, the preferred user name and account information are used by the setup process for that
     * user.
     *
     * This API should only be called if the current user is an {@link #isAdminUser() admin} user,
     * as otherwise the returned intent will not be able to create a user.
     *
     * @param userName Optional name to assign to the user. Character limit is 100.
     * @param accountName Optional account name that will be used by the setup wizard to initialize
     *                    the user. Character limit is 500.
     * @param accountType Optional account type for the account to be created. This is required
     *                    if the account name is specified. Character limit is 500.
     * @param accountOptions Optional bundle of data to be passed in during account creation in the
     *                       new user via {@link AccountManager#addAccount(String, String, String[],
     *                       Bundle, android.app.Activity, android.accounts.AccountManagerCallback,
     *                       Handler)}. Character limit is 1000.
     * @return An Intent that can be launched from an Activity.
     * @see #USER_CREATION_FAILED_NOT_PERMITTED
     * @see #USER_CREATION_FAILED_NO_MORE_USERS
     * @see #supportsMultipleUsers
     */
    public static Intent createUserCreationIntent(@Nullable String userName,
            @Nullable String accountName,
            @Nullable String accountType, @Nullable PersistableBundle accountOptions) {
        Intent intent = new Intent(ACTION_CREATE_USER);
        if (userName != null) {
            intent.putExtra(EXTRA_USER_NAME, userName);
        }
        if (accountName != null && accountType == null) {
            throw new IllegalArgumentException("accountType must be specified if accountName is "
                    + "specified");
        }
        if (accountName != null) {
            intent.putExtra(EXTRA_USER_ACCOUNT_NAME, accountName);
        }
        if (accountType != null) {
            intent.putExtra(EXTRA_USER_ACCOUNT_TYPE, accountType);
        }
        if (accountOptions != null) {
            intent.putExtra(EXTRA_USER_ACCOUNT_OPTIONS, accountOptions);
        }
        return intent;
    }

    /**
     * Returns the list of the system packages that would be installed on this type of user upon
     * its creation.
     *
     * Returns {@code null} if all system packages would be installed.
     *
     * @hide
     */
    @TestApi
    @SuppressLint("NullableCollection")
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.CREATE_USERS
    })
    public @Nullable Set<String> getPreInstallableSystemPackages(@NonNull String userType) {
        try {
            final String[] installableSystemPackages
                    = mService.getPreInstallableSystemPackages(userType);
            if (installableSystemPackages == null) {
                return null;
            }
            return new ArraySet<>(installableSystemPackages);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     *
     * Returns the preferred account name for the context user's creation.
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    @UserHandleAware(enabledSinceTargetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public String getSeedAccountName() {
        try {
            return mService.getSeedAccountName(getContextUserIfAppropriate());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     *
     * Returns the preferred account type for the context user's creation.
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    @UserHandleAware(enabledSinceTargetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public String getSeedAccountType() {
        try {
            return mService.getSeedAccountType(getContextUserIfAppropriate());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     *
     * Returns the preferred account's options bundle for user creation.
     * @return Any options set by the requestor that created the context user.
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    @UserHandleAware(enabledSinceTargetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public PersistableBundle getSeedAccountOptions() {
        try {
            return mService.getSeedAccountOptions(getContextUserIfAppropriate());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     *
     * Called by a system activity to set the seed account information of a user created
     * through the user creation intent.
     * @param userId
     * @param accountName
     * @param accountType
     * @param accountOptions
     * @see #createUserCreationIntent(String, String, String, PersistableBundle)
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public void setSeedAccountData(int userId, String accountName, String accountType,
            PersistableBundle accountOptions) {
        try {
            mService.setSeedAccountData(userId, accountName, accountType, accountOptions,
                    /* persist= */ true);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Clears the seed information used to create the context user.
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    @UserHandleAware(enabledSinceTargetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public void clearSeedAccountData() {
        try {
            mService.clearSeedAccountData(getContextUserIfAppropriate());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Marks the guest user for deletion to allow a new guest to be created before deleting
     * the current user who is a guest.
     * @param userId
     * @return
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public boolean markGuestForDeletion(@UserIdInt int userId) {
        try {
            return mService.markGuestForDeletion(userId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the user as enabled, if such an user exists.
     *
     * <p>Note that the default is true, it's only that managed profiles might not be enabled.
     * Also ephemeral users can be disabled to indicate that their removal is in progress and they
     * shouldn't be re-entered. Therefore ephemeral users should not be re-enabled once disabled.
     *
     * @param userId the id of the profile to enable
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public void setUserEnabled(@UserIdInt int userId) {
        try {
            mService.setUserEnabled(userId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Assigns admin privileges to the user, if such a user exists.
     *
     * <p>Note that this does not alter the user's pre-existing user restrictions.
     *
     * @param userId the id of the user to become admin
     * @hide
     */
    @RequiresPermission(allOf = {
            Manifest.permission.INTERACT_ACROSS_USERS_FULL,
            Manifest.permission.MANAGE_USERS
    })
    public void setUserAdmin(@UserIdInt int userId) {
        try {
            mService.setUserAdmin(userId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Revokes admin privileges from the user, if such a user exists.
     *
     * <p>Note that this does not alter the user's pre-existing user restrictions.
     *
     * @param userId the id of the user to revoke admin rights from
     * @hide
     */
    @RequiresPermission(allOf = {
            Manifest.permission.INTERACT_ACROSS_USERS_FULL,
            Manifest.permission.MANAGE_USERS
    })
    public void revokeUserAdmin(@UserIdInt int userId) {
        try {
            mService.revokeUserAdmin(userId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Evicts the user's credential encryption key from memory by stopping and restarting the user.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public void evictCredentialEncryptionKey(@UserIdInt int userId) {
        try {
            mService.evictCredentialEncryptionKey(userId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Return the number of users currently created on the device.
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.CREATE_USERS
    })
    public int getUserCount() {
        List<UserInfo> users = getUsers();
        return users != null ? users.size() : 1;
    }

    /**
     * Returns information for all fully-created users on this device, including ones marked for
     * deletion.
     *
     * <p>To retrieve only users that are not marked for deletion, use {@link #getAliveUsers()}.
     *
     * <p>To retrieve *all* users (including partial and pre-created users), use
     * {@link #getUsers(boolean, boolean, boolean)) getUsers(false, false, false)}.
     *
     * <p>To retrieve a more specific list of users, use
     * {@link #getUsers(boolean, boolean, boolean)}.
     *
     * @return the list of users that were created.
     *
     * @hide
     */
    @UnsupportedAppUsage
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.CREATE_USERS
    })
    @TestApi
    public @NonNull List<UserInfo> getUsers() {
        return getUsers(/*excludePartial= */ true, /* excludeDying= */ false,
                /* excludePreCreated= */ true);
    }

    /**
     * Returns information for all "usable" users on this device (i.e, it excludes users that are
     * marked for deletion, pre-created users, etc...).
     *
     * <p>To retrieve all fully-created users, use {@link #getUsers()}.
     *
     * <p>To retrieve a more specific list of users, use
     * {@link #getUsers(boolean, boolean, boolean)}.
     *
     * @return the list of users that were created.
     * @hide
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.CREATE_USERS
    })
    @TestApi
    public @NonNull List<UserInfo> getAliveUsers() {
        return getUsers(/*excludePartial= */ true, /* excludeDying= */ true,
                /* excludePreCreated= */ true);
    }

    /**
     * @deprecated use {@link #getAliveUsers()} for {@code getUsers(true)}, or
     * {@link #getUsers()} for @code getUsers(false)}.
     *
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.CREATE_USERS
    })
    public @NonNull List<UserInfo> getUsers(boolean excludeDying) {
        return getUsers(/*excludePartial= */ true, excludeDying,
                /* excludePreCreated= */ true);
    }

    /**
     * Returns information for all users on this device, based on the filtering parameters.
     *
     * @deprecated Pre-created users are deprecated and no longer supported.
     *             Use {@link #getUsers()}, or {@link #getAliveUsers()} instead.
     * @hide
     */
    @Deprecated
    @TestApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.CREATE_USERS
    })
    public @NonNull List<UserInfo> getUsers(boolean excludePartial, boolean excludeDying,
            boolean excludePreCreated) {
        try {
            return mService.getUsers(excludePartial, excludeDying, excludePreCreated);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the user handles for all users on this device, based on the filtering parameters.
     *
     * @param excludeDying specify if the list should exclude users being removed.
     * @return the list of user handles.
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.CREATE_USERS
    })
    public @NonNull List<UserHandle> getUserHandles(boolean excludeDying) {
        List<UserInfo> users = getUsers(/* excludePartial= */ true, excludeDying,
                /* excludePreCreated= */ true);
        List<UserHandle> result = new ArrayList<>(users.size());
        for (UserInfo user : users) {
            result.add(user.getUserHandle());
        }
        return result;
    }

    /**
     * Returns serial numbers of all users on this device.
     *
     * @param excludeDying specify if the list should exclude users being removed.
     * @return the list of serial numbers of users that exist on the device.
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.CREATE_USERS
    })
    public long[] getSerialNumbersOfUsers(boolean excludeDying) {
        List<UserInfo> users = getUsers(/* excludePartial= */ true, excludeDying,
                /* excludePreCreated= */ true);
        long[] result = new long[users.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = users.get(i).serialNumber;
        }
        return result;
    }

    /**
     * @return the user's account name, null if not found.
     * @hide
     */
    @RequiresPermission( allOf = {
            Manifest.permission.INTERACT_ACROSS_USERS_FULL,
            Manifest.permission.MANAGE_USERS
    })
    public @Nullable String getUserAccount(@UserIdInt int userId) {
        try {
            return mService.getUserAccount(userId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Set account name for the given user.
     * @hide
     */
    @RequiresPermission( allOf = {
            Manifest.permission.INTERACT_ACROSS_USERS_FULL,
            Manifest.permission.MANAGE_USERS
    })
    public void setUserAccount(@UserIdInt int userId, @Nullable String accountName) {
        try {
            mService.setUserAccount(userId, accountName);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns information for Primary user (which in practice is the same as the System user).
     *
     * @return the Primary user, null if not found.
     * @deprecated For the system user, call {@link #getUserInfo} on {@link UserHandle#USER_SYSTEM},
     *             or just use {@link UserHandle#SYSTEM} or {@link UserHandle#USER_SYSTEM}.
     *             For the designated MainUser, use {@link #getMainUser()}.
     *
     * @hide
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public @Nullable UserInfo getPrimaryUser() {
        try {
            return mService.getPrimaryUser();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the user who was last in the foreground, not including the current user and not
     * including profiles.
     *
     * <p>Returns {@code null} if there is no previous user, for example if there
     * is only one full user (i.e. only one user which is not a profile) on the device.
     *
     * <p>This method may be used for example to find the user to switch back to if the
     * current user is removed, or if creating a new user is aborted.
     *
     * <p>Note that reboots do not interrupt this calculation; the previous user need not have
     * used the device since it rebooted.
     *
     * <p>Note also that on devices that support multiple users on multiple displays, it is possible
     * that the returned user will be visible on a secondary display, as the foreground user is the
     * one associated with the main display.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.CREATE_USERS,
            android.Manifest.permission.QUERY_USERS
    })
    public @Nullable UserHandle getPreviousForegroundUser() {
        try {
            final int previousUser = mService.getPreviousFullUserToEnterForeground();
            if (previousUser == UserHandle.USER_NULL) {
                return null;
            }
            return UserHandle.of(previousUser);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Checks whether it's possible to add more users.
     *
     * @return true if more users can be added, false if limit has been reached.
     *
     * @deprecated use {@link #canAddMoreUsers(String)} instead.
     *
     * @hide
     */
    @Deprecated
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.CREATE_USERS
    })
    public boolean canAddMoreUsers() {
        // TODO(b/142482943): UMS has different logic, excluding Demo and Profile from counting. Why
        //                    not here? The logic is inconsistent. See UMS.canAddMoreManagedProfiles
        final List<UserInfo> users = getAliveUsers();
        final int totalUserCount = users.size();
        int aliveUserCount = 0;
        for (int i = 0; i < totalUserCount; i++) {
            UserInfo user = users.get(i);
            if (!user.isGuest()) {
                aliveUserCount++;
            }
        }
        return aliveUserCount < getMaxSupportedUsers();
    }

    /**
     * Checks whether it is possible to add more users of the given user type.
     *
     * @param userType the type of user, such as {@link UserManager#USER_TYPE_FULL_SECONDARY}.
     * @return true if more users of the given type can be added, otherwise false.
     * @hide
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.CREATE_USERS
    })
    public boolean canAddMoreUsers(@NonNull String userType) {
        try {
            if (userType.equals(USER_TYPE_FULL_GUEST)) {
                return mService.canAddMoreUsersOfType(userType);
            } else {
                return canAddMoreUsers() && mService.canAddMoreUsersOfType(userType);
            }
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the remaining number of users of the given type that can be created.
     *
     * @param userType the type of user, such as {@link UserManager#USER_TYPE_FULL_SECONDARY}.
     * @return how many additional users can be created.
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.CREATE_USERS,
            android.Manifest.permission.QUERY_USERS
    })
    public int getRemainingCreatableUserCount(@NonNull String userType) {
        Objects.requireNonNull(userType, "userType must not be null");
        try {
            return mService.getRemainingCreatableUserCount(userType);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the remaining number of profiles that can be added to the context user.
     * <p>Note that is applicable to any profile type (currently not including Restricted profiles).
     *
     * @param userType the type of profile, such as {@link UserManager#USER_TYPE_PROFILE_MANAGED}.
     * @return how many additional profiles can be created.
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.CREATE_USERS,
            android.Manifest.permission.QUERY_USERS
    })
    @UserHandleAware
    public int getRemainingCreatableProfileCount(@NonNull String userType) {
        Objects.requireNonNull(userType, "userType must not be null");
        try {
            // TODO(b/142482943): Perhaps let the following code apply to restricted users too.
            return mService.getRemainingCreatableProfileCount(userType, mUserId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Checks whether it's possible to add more managed profiles.
     * if allowedToRemoveOne is true and if the user already has a managed profile, then return if
     * we could add a new managed profile to this user after removing the existing one.
     *
     * @return true if more managed profiles can be added, false if limit has been reached.
     * @hide
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.CREATE_USERS,
            android.Manifest.permission.QUERY_USERS
    })
    public boolean canAddMoreManagedProfiles(@UserIdInt int userId, boolean allowedToRemoveOne) {
        try {
            return mService.canAddMoreManagedProfiles(userId, allowedToRemoveOne);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Checks whether it's possible to add more profiles of the given type to the given user.
     *
     * @param userType the type of user, such as {@link UserManager#USER_TYPE_PROFILE_MANAGED}.
     * @return true if more profiles can be added, false if limit has been reached.
     * @hide
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.CREATE_USERS,
            android.Manifest.permission.QUERY_USERS
    })
    public boolean canAddMoreProfilesToUser(@NonNull String userType, @UserIdInt int userId) {
        try {
            return mService.canAddMoreProfilesToUser(userType, userId, false);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Checks whether this device supports users of the given user type.
     *
     * @param userType the type of user, such as {@link UserManager#USER_TYPE_FULL_SECONDARY}.
     * @return true if the creation of users of the given user type is enabled on this device.
     * @hide
     */
    @TestApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.CREATE_USERS
    })
    public boolean isUserTypeEnabled(@NonNull String userType) {
        try {
            return mService.isUserTypeEnabled(userType);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns list of the profiles of userId including userId itself.
     * Note that this returns both enabled and not enabled profiles. See
     * {@link #getEnabledProfiles(int)} if you need only the enabled ones.
     * <p>Note that this includes all profile types (not including Restricted profiles).
     *
     * <p>Requires {@link android.Manifest.permission#MANAGE_USERS} or
     * {@link android.Manifest.permission#CREATE_USERS} or
     * {@link android.Manifest.permission#QUERY_USERS} if userId is not the calling user.
     * @param userId profiles of this user will be returned.
     * @return the list of profiles.
     * @hide
     */
    @UnsupportedAppUsage
    @RequiresPermission(anyOf = {
            Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS,
            Manifest.permission.QUERY_USERS}, conditional = true)
    public List<UserInfo> getProfiles(@UserIdInt int userId) {
        try {
            return mService.getProfiles(userId, false /* enabledOnly */);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns list of the profiles of the given user, including userId itself, as well as the
     * communal profile, if there is one.
     *
     * <p>Note that this returns both enabled and not enabled profiles.
     * <p>Note that this includes all profile types (not including Restricted profiles).
     *
     * @hide
     */
    @FlaggedApi(android.multiuser.Flags.FLAG_SUPPORT_COMMUNAL_PROFILE)
    @RequiresPermission(anyOf = {
            Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS,
            Manifest.permission.QUERY_USERS})
    public List<UserInfo> getProfilesIncludingCommunal(@UserIdInt int userId) {
        final List<UserInfo> profiles = getProfiles(userId);
        final UserHandle communalProfile = getCommunalProfile();
        if (communalProfile != null) {
            final UserInfo communalInfo = getUserInfo(communalProfile.getIdentifier());
            if (communalInfo != null) {
                profiles.add(communalInfo);
            }
        }
        return profiles;
    }

    /**
     * Checks if the 2 provided user handles belong to the same profile group.
     *
     * @param user one of the two user handles to check.
     * @param otherUser one of the two user handles to check.
     * @return true if the two users are in the same profile group.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.QUERY_USERS})
    public boolean isSameProfileGroup(@NonNull UserHandle user, @NonNull UserHandle otherUser) {
        return isSameProfileGroup(user.getIdentifier(), otherUser.getIdentifier());
    }

    /**
     * Checks if the 2 provided user ids belong to the same profile group.
     * @param userId one of the two user ids to check.
     * @param otherUserId one of the two user ids to check.
     * @return true if the two user ids are in the same profile group.
     * @hide
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.QUERY_USERS})
    public boolean isSameProfileGroup(@UserIdInt int userId, int otherUserId) {
        try {
            return mService.isSameProfileGroup(userId, otherUserId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns list of the profiles of userId including userId itself.
     * Note that this returns only enabled.
     * <p>Note that this includes all profile types (not including Restricted profiles).
     *
     * <p>Requires {@link android.Manifest.permission#MANAGE_USERS} or
     * {@link android.Manifest.permission#CREATE_USERS} or
     * {@link android.Manifest.permission#QUERY_USERS} if userId is not the calling user.
     * @param userId profiles of this user will be returned.
     * @return the list of profiles.
     * @deprecated use {@link #getUserProfiles()} instead.
     *
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
    @RequiresPermission(anyOf = {
            Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS,
            Manifest.permission.QUERY_USERS}, conditional = true)
    public List<UserInfo> getEnabledProfiles(@UserIdInt int userId) {
        try {
            return mService.getProfiles(userId, true /* enabledOnly */);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a list of UserHandles for profiles associated with the context user, including the
     * user itself.
     * <p>Note that this includes all profile types (not including Restricted profiles).
     *
     * @return A non-empty list of UserHandles associated with the context user.
     */
    @UserHandleAware(
            enabledSinceTargetSdkVersion = Build.VERSION_CODES.TIRAMISU,
            requiresAnyOfPermissionsIfNotCaller = {
                    Manifest.permission.MANAGE_USERS,
                    Manifest.permission.CREATE_USERS,
                    Manifest.permission.QUERY_USERS})
    public List<UserHandle> getUserProfiles() {
        int[] userIds = getProfileIds(getContextUserIfAppropriate(), true /* enabledOnly */);
        return convertUserIdsToUserHandles(userIds);
    }

    /**
     * Returns a list of ids for enabled profiles associated with the context user including the
     * user itself.
     * <p>Note that this includes all profile types (not including Restricted profiles).
     *
     * @return A non-empty list of UserHandles associated with the context user.
     * @hide
     */
    @SystemApi
    @UserHandleAware(
            requiresAnyOfPermissionsIfNotCaller = {
                    Manifest.permission.MANAGE_USERS,
                    Manifest.permission.CREATE_USERS,
                    Manifest.permission.QUERY_USERS})
    public @NonNull List<UserHandle> getEnabledProfiles() {
        return getProfiles(true);
    }

    /**
     * Returns a list of ids for all profiles associated with the context user including the user
     * itself.
     * <p>Note that this includes all profile types (not including Restricted profiles).
     *
     * @return A non-empty list of UserHandles associated with the context user.
     * @hide
     */
    @SystemApi
    @UserHandleAware(
            requiresAnyOfPermissionsIfNotCaller = {
                    Manifest.permission.MANAGE_USERS,
                    Manifest.permission.CREATE_USERS,
                    Manifest.permission.QUERY_USERS})
    public @NonNull List<UserHandle> getAllProfiles() {
        return getProfiles(false);
    }

    /**
     * Returns a list of ids for profiles associated with the context user including the user
     * itself.
     * <p>Note that this includes all profile types (not including Restricted profiles).
     *
     * @param enabledOnly whether to return only {@link UserInfo#isEnabled() enabled} profiles
     * @return A non-empty list of UserHandles associated with the context user.
     */
    @UserHandleAware(
            requiresAnyOfPermissionsIfNotCaller = {
                    Manifest.permission.MANAGE_USERS,
                    Manifest.permission.CREATE_USERS,
                    Manifest.permission.QUERY_USERS})
    private @NonNull List<UserHandle> getProfiles(boolean enabledOnly) {
        final int[] userIds = getProfileIds(mUserId, enabledOnly);
        return convertUserIdsToUserHandles(userIds);
    }

    /** Converts the given array of userIds to a List of UserHandles. */
    private @NonNull List<UserHandle> convertUserIdsToUserHandles(@NonNull int[] userIds) {
        final List<UserHandle> result = new ArrayList<>(userIds.length);
        for (int userId : userIds) {
            result.add(UserHandle.of(userId));
        }
        return result;
    }

    /**
     * Returns a list of ids for profiles associated with the specified user including the user
     * itself.
     * <p>Note that this includes all profile types (not including Restricted profiles).
     *
     * @param userId      id of the user to return profiles for
     * @param enabledOnly whether return only {@link UserInfo#isEnabled() enabled} profiles
     * @return A non-empty list of ids of profiles associated with the specified user.
     *
     * @hide
     */
    @RequiresPermission(anyOf = {
            Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS,
            Manifest.permission.QUERY_USERS}, conditional = true)
    public @NonNull int[] getProfileIds(@UserIdInt int userId, boolean enabledOnly) {
        try {
            return mService.getProfileIds(userId, enabledOnly);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @see #getProfileIds(int, boolean)
     * @hide
     */
    @UnsupportedAppUsage
    @RequiresPermission(anyOf = {
            Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS,
            Manifest.permission.QUERY_USERS}, conditional = true)
    public int[] getProfileIdsWithDisabled(@UserIdInt int userId) {
        return getProfileIds(userId, false /* enabledOnly */);
    }

    /**
     * @see #getProfileIds(int, boolean)
     * @hide
     */
    @RequiresPermission(anyOf = {
            Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS,
            Manifest.permission.QUERY_USERS}, conditional = true)
    public int[] getEnabledProfileIds(@UserIdInt int userId) {
        return getProfileIds(userId, true /* enabledOnly */);
    }

    /**
     * Returns the device credential owner id of the profile from
     * which this method is called, or userId if called from a user that
     * is not a profile.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_USERS)
    public int getCredentialOwnerProfile(@UserIdInt int userId) {
        try {
            return mService.getCredentialOwnerProfile(userId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the parent of the profile which this method is called from
     * or null if it has no parent (e.g. if it is not a profile).
     *
     * @hide
     */
    @UnsupportedAppUsage
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.INTERACT_ACROSS_USERS
    })
    public UserInfo getProfileParent(@UserIdInt int userId) {
        try {
            return mService.getProfileParent(userId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Get the parent of a user profile.
     *
     * @param user the handle of the user profile
     *
     * @return the parent of the user or {@code null} if the user has no parent
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.INTERACT_ACROSS_USERS
    })
    public @Nullable UserHandle getProfileParent(@NonNull UserHandle user) {
        UserInfo info = getProfileParent(user.getIdentifier());

        if (info == null) {
            return null;
        }

        return UserHandle.of(info.id);
    }

    /**
     * Enables or disables quiet mode for a managed profile. If quiet mode is enabled, apps in a
     * managed profile don't run, generate notifications, or consume data or battery.
     * <p>
     * If a user's credential is needed to turn off quiet mode, a confirm credential screen will be
     * shown to the user.
     * <p>
     * The change may not happen instantly, however apps can listen for
     * {@link Intent#ACTION_MANAGED_PROFILE_AVAILABLE} and
     * {@link Intent#ACTION_MANAGED_PROFILE_UNAVAILABLE} broadcasts in order to be notified of
     * the change of the quiet mode. Apps can also check the current state of quiet mode by
     * calling {@link #isQuietModeEnabled(UserHandle)}.
     * <p>
     * The caller must either be the foreground default launcher or have one of these permissions:
     * {@code MANAGE_USERS} or {@code MODIFY_QUIET_MODE}.
     *
     * @param enableQuietMode whether quiet mode should be enabled or disabled
     * @param userHandle user handle of the profile
     * @return {@code false} if user's credential is needed in order to turn off quiet mode,
     *         {@code true} otherwise
     * @throws SecurityException if the caller is invalid
     * @throws IllegalArgumentException if {@code userHandle} is not a managed profile
     *
     * @see #isQuietModeEnabled(UserHandle)
     */
    @RequiresPermission(anyOf = {android.Manifest.permission.MANAGE_USERS,
            Manifest.permission.MODIFY_QUIET_MODE}, conditional = true)
    public boolean requestQuietModeEnabled(boolean enableQuietMode, @NonNull UserHandle userHandle) {
        return requestQuietModeEnabled(enableQuietMode, userHandle, null);
    }

    /**
     * Perform the same operation as {@link #requestQuietModeEnabled(boolean, UserHandle)}, but
     * with a flag to tweak the behavior of the request.
     *
     * @param enableQuietMode whether quiet mode should be enabled or disabled
     * @param userHandle user handle of the profile
     * @param flags Can be 0 or {@link #QUIET_MODE_DISABLE_ONLY_IF_CREDENTIAL_NOT_REQUIRED}.
     * @return {@code false} if user's credential is needed in order to turn off quiet mode,
     *         {@code true} otherwise
     * @throws SecurityException if the caller is invalid
     * @throws IllegalArgumentException if {@code userHandle} is not a managed profile
     *
     * @see #isQuietModeEnabled(UserHandle)
     */
    public boolean requestQuietModeEnabled(boolean enableQuietMode, @NonNull UserHandle userHandle,
            @QuietModeFlag int flags) {
        return requestQuietModeEnabled(enableQuietMode, userHandle, null, flags);
    }

    /**
     * Similar to {@link #requestQuietModeEnabled(boolean, UserHandle)}, except you can specify
     * a target to start when user is unlocked. If {@code target} is specified, caller must have
     * the {@link android.Manifest.permission#MANAGE_USERS} permission.
     *
     * @see {@link #requestQuietModeEnabled(boolean, UserHandle)}
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public boolean requestQuietModeEnabled(
            boolean enableQuietMode, @NonNull UserHandle userHandle, IntentSender target) {
        return requestQuietModeEnabled(enableQuietMode, userHandle, target, 0);
    }

    /**
     * Similar to {@link #requestQuietModeEnabled(boolean, UserHandle)}, except you can specify
     * a target to start when user is unlocked. If {@code target} is specified, caller must have
     * the {@link android.Manifest.permission#MANAGE_USERS} permission.
     *
     * @see #requestQuietModeEnabled(boolean, UserHandle)
     * @hide
     */
    public boolean requestQuietModeEnabled(
            boolean enableQuietMode, @NonNull UserHandle userHandle, IntentSender target,
            int flags) {
        try {
            return mService.requestQuietModeEnabled(
                    mContext.getPackageName(), enableQuietMode, userHandle.getIdentifier(), target,
                    flags);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the given profile is in quiet mode or not.
     * Notes: Quiet mode is only supported for managed profiles.
     *
     * @param userHandle The user handle of the profile to be queried.
     * @return true if the profile is in quiet mode, false otherwise.
     */
    public boolean isQuietModeEnabled(UserHandle userHandle) {
        try {
            return mService.isQuietModeEnabled(userHandle.getIdentifier());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the given user has a badge (generally to put on profiles' icons).
     *
     * @param userId userId of the user in question
     * @return true if the user's icons should display a badge; false otherwise.
     *
     * @see #getBadgedIconForUser more information about badging in general
     * @hide
     */
    public boolean hasBadge(@UserIdInt int userId) {
        if (!isProfile(userId)) {
            // Since currently only profiles actually have badges, we can do this optimization.
            return false;
        }
        try {
            return mService.hasBadge(userId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the user associated with the context has a badge (generally to put on
     * profiles' icons).
     *
     * @return true if the user's icons should display a badge; false otherwise.
     * @see #getBadgedIconForUser more information about badging in general
     * @hide
     */
    @UserHandleAware
    public boolean hasBadge() {
        return hasBadge(mUserId);
    }

    /**
     * Returns the light theme badge color for the given user (generally to color a profile's
     * icon's badge).
     *
     * <p>To check whether a badge color is expected for the user, first call {@link #hasBadge}.
     *
     * @return the color (not the resource ID) to be used for the user's badge
     * @throws Resources.NotFoundException if no valid badge color exists for this user
     *
     * @see #getBadgedIconForUser more information about badging in general
     * @hide
     */
    public @ColorInt int getUserBadgeColor(@UserIdInt int userId) {
        try {
            final int resourceId = mService.getUserBadgeColorResId(userId);
            return Resources.getSystem().getColor(resourceId, null);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the dark theme badge color for the given user (generally to color a profile's icon's
     * badge).
     *
     * <p>To check whether a badge color is expected for the user, first call {@link #hasBadge}.
     *
     * @return the color (not the resource ID) to be used for the user's badge
     * @throws Resources.NotFoundException if no valid badge color exists for this user
     *
     * @see #getBadgedIconForUser more information about badging in general
     * @hide
     */
    public @ColorInt int getUserBadgeDarkColor(@UserIdInt int userId) {
        try {
            final int resourceId = mService.getUserBadgeDarkColorResId(userId);
            return Resources.getSystem().getColor(resourceId, null);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the Resource ID of the user's icon badge.
     *
     * @return the Resource ID of the user's icon badge if it has one; otherwise
     *         {@link Resources#ID_NULL}.
     *
     * @see #getBadgedIconForUser more information about badging in general
     * @hide
     */
    public @DrawableRes int getUserIconBadgeResId(@UserIdInt int userId) {
        try {
            return mService.getUserIconBadgeResId(userId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the Resource ID of the user's badge.
     *
     * @return the Resource ID of the user's badge if it has one; otherwise
     *         {@link Resources#ID_NULL}.
     *
     * @see #getBadgedIconForUser more information about badging in general
     * @hide
     */
    public @DrawableRes int getUserBadgeResId(@UserIdInt int userId) {
        try {
            return mService.getUserBadgeResId(userId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the Resource ID of the user's badge without a background.
     *
     * @return the Resource ID of the user's no-background badge if it has one; otherwise
     *         {@link Resources#ID_NULL}.
     *
     * @see #getBadgedIconForUser more information about badging in general
     * @hide
     */
    public @DrawableRes int getUserBadgeNoBackgroundResId(@UserIdInt int userId) {
        try {
            return mService.getUserBadgeNoBackgroundResId(userId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the Resource ID of the user's status bar icon.
     *
     * @return the Resource ID of the user's status bar icon if it has one; otherwise
     *         {@link Resources#ID_NULL}.
     * @hide
     */
    public @DrawableRes int getUserStatusBarIconResId(@UserIdInt int userId) {
        try {
            return mService.getUserStatusBarIconResId(userId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * If the target user is a profile of the calling user or the caller
     * is itself a profile, then this returns a badged copy of the given
     * icon to be able to distinguish it from the original icon. For badging an
     * arbitrary drawable use {@link #getBadgedDrawableForUser(
     * android.graphics.drawable.Drawable, UserHandle, android.graphics.Rect, int)}.
     * <p>
     * If the original drawable is a BitmapDrawable and the backing bitmap is
     * mutable as per {@link android.graphics.Bitmap#isMutable()}, the badging
     * is performed in place and the original drawable is returned.
     * </p>
     *
     * @param icon The icon to badge.
     * @param user The target user.
     * @return A drawable that combines the original icon and a badge as
     *         determined by the system.
     * @removed
     */
    public Drawable getBadgedIconForUser(Drawable icon, UserHandle user) {
        return mContext.getPackageManager().getUserBadgedIcon(icon, user);
    }

    /**
     * If the target user is a profile of the calling user or the caller
     * is itself a profile, then this returns a badged copy of the given
     * drawable allowing the user to distinguish it from the original drawable.
     * The caller can specify the location in the bounds of the drawable to be
     * badged where the badge should be applied as well as the density of the
     * badge to be used.
     * <p>
     * If the original drawable is a BitmapDrawable and the backing bitmap is
     * mutable as per {@link android.graphics.Bitmap#isMutable()}, the badging
     * is performed in place and the original drawable is returned.
     * </p>
     *
     * @param badgedDrawable The drawable to badge.
     * @param user The target user.
     * @param badgeLocation Where in the bounds of the badged drawable to place
     *         the badge. If it's {@code null}, the badge is applied on top of the entire
     *         drawable being badged.
     * @param badgeDensity The optional desired density for the badge as per
     *         {@link android.util.DisplayMetrics#densityDpi}. If it's not positive,
     *         the density of the display is used.
     * @return A drawable that combines the original drawable and a badge as
     *         determined by the system.
     * @removed
     */
    public Drawable getBadgedDrawableForUser(Drawable badgedDrawable, UserHandle user,
            Rect badgeLocation, int badgeDensity) {
        return mContext.getPackageManager().getUserBadgedDrawableForDensity(badgedDrawable, user,
                badgeLocation, badgeDensity);
    }

    /**
     * Retrieves a user badge associated with the current context user. This is only
     * applicable to profile users since non-profile users do not have badges.
     *
     * @return A {@link Drawable} user badge corresponding to the context user
     * @throws android.content.res.Resources.NotFoundException if the user is not a profile or
     * does not have a badge defined.
     * @hide
     */
    @SystemApi
    @UserHandleAware(
            requiresAnyOfPermissionsIfNotCallerProfileGroup = {
                    Manifest.permission.MANAGE_USERS,
                    Manifest.permission.INTERACT_ACROSS_USERS})
    @SuppressLint("UnflaggedApi") // b/306636213
    public @NonNull Drawable getUserBadge() {
        if (!isProfile(mUserId)) {
            throw new Resources.NotFoundException("No badge found for this user.");
        }
        if (isManagedProfile(mUserId)) {
            DevicePolicyManager dpm = mContext.getSystemService(DevicePolicyManager.class);
            return dpm.getResources().getDrawable(
                    android.app.admin.DevicePolicyResources.Drawables.WORK_PROFILE_ICON_BADGE,
                    SOLID_COLORED, () -> getDefaultUserBadge(mUserId));
        }
        return getDefaultUserBadge(mUserId);
    }

    private Drawable getDefaultUserBadge(@UserIdInt int userId){
        return mContext.getResources().getDrawable(getUserBadgeResId(userId), mContext.getTheme());
    }

    /**
     * If the target user is a profile of the calling user or the caller
     * is itself a profile, then this returns a copy of the label with
     * badging for accessibility services like talkback. E.g. passing in "Email"
     * and it might return "Work Email" for Email in the work profile.
     *
     * <p>Requires {@link android.Manifest.permission#MANAGE_USERS} or
     * {@link android.Manifest.permission#INTERACT_ACROSS_USERS} permission, otherwise the caller
     * must be in the same profile group of specified user.
     *
     * @param label The label to change.
     * @param user The target user.
     * @return A label that combines the original label and a badge as
     *         determined by the system.
     * @removed
     */
    public CharSequence getBadgedLabelForUser(CharSequence label, UserHandle user) {
        final int userId = user.getIdentifier();
        if (!hasBadge(userId)) {
            return label;
        }
        DevicePolicyManager dpm = mContext.getSystemService(DevicePolicyManager.class);
        return dpm.getResources().getString(
                getUpdatableUserBadgedLabelId(userId),
                () -> getDefaultUserBadgedLabel(label, userId),
                /* formatArgs= */ label);
    }

    private String getUpdatableUserBadgedLabelId(int userId) {
        return isManagedProfile(userId) ? WORK_PROFILE_BADGED_LABEL : UNDEFINED;
    }

    private String getDefaultUserBadgedLabel(CharSequence label, int userId) {
        try {
            final int resourceId = mService.getUserBadgeLabelResId(userId);
            return Resources.getSystem().getString(resourceId, label);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the string/label that should be used to represent the context user. For example,
     * this string can represent a profile in tabbed views. This is only applicable to
     * {@link #isProfile() profile users}. This string is translated to the device default language.
     *
     * @return String representing the label for the context user.
     *
     * @throws android.content.res.Resources.NotFoundException if the user does not have a label
     * defined.
     *
     * @hide
     */
    @SystemApi
    @SuppressLint("UnflaggedApi") // b/306636213
    @UserHandleAware(
            requiresAnyOfPermissionsIfNotCallerProfileGroup = {
                    Manifest.permission.MANAGE_USERS,
                    Manifest.permission.QUERY_USERS,
                    Manifest.permission.INTERACT_ACROSS_USERS})
    public @NonNull String getProfileLabel() {
        if (isManagedProfile(mUserId)) {
            DevicePolicyManager dpm = mContext.getSystemService(DevicePolicyManager.class);
            return dpm.getResources().getString(
                    android.app.admin.DevicePolicyResources.Strings.Core.RESOLVER_WORK_TAB,
                    () -> getDefaultProfileLabel(mUserId));
        }
        return getDefaultProfileLabel(mUserId);
    }

    private String getDefaultProfileLabel(int userId) {
        try {
            final int resourceId = mService.getProfileLabelResId(userId);
            return Resources.getSystem().getString(resourceId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * If the user is a {@link UserManager#isProfile profile}, checks if the user
     * shares media with its parent user (the user that created this profile).
     * Returns false for any other type of user.
     *
     * @return true if the user shares media with its parent user, false otherwise.
     *
     * @deprecated use {@link #getUserProperties(UserHandle)} with
     *            {@link UserProperties#isMediaSharedWithParent()} instead.
     * @hide
     */
    @SystemApi
    @Deprecated
    @UserHandleAware(
            requiresAnyOfPermissionsIfNotCallerProfileGroup = {
                    Manifest.permission.MANAGE_USERS,
                    Manifest.permission.QUERY_USERS,
                    Manifest.permission.INTERACT_ACROSS_USERS})
    @SuppressAutoDoc
    public boolean isMediaSharedWithParent() {
        try {
            return getUserProperties(UserHandle.of(mUserId)).isMediaSharedWithParent();
        } catch (IllegalArgumentException e) {
            // If the user doesn't exist, return false (for historical reasons)
            return false;
        }
    }

    /**
     * Returns whether the user can have shared lockscreen credential with its parent user.
     *
     * This API only works for {@link UserManager#isProfile() profiles}
     * and will always return false for any other user type.
     *
     * @deprecated use {@link #getUserProperties(UserHandle)} with
     *            {@link UserProperties#isCredentialShareableWithParent()} instead.
     * @hide
     */
    @SystemApi
    @Deprecated
    @UserHandleAware(
            requiresAnyOfPermissionsIfNotCallerProfileGroup = {
                    Manifest.permission.MANAGE_USERS,
                    Manifest.permission.QUERY_USERS,
                    Manifest.permission.INTERACT_ACROSS_USERS})
    @SuppressAutoDoc
    public boolean isCredentialSharableWithParent() {
        try {
            return getUserProperties(UserHandle.of(mUserId)).isCredentialShareableWithParent();
        } catch (IllegalArgumentException e) {
            // If the user doesn't exist, return false (for historical reasons)
            return false;
        }
    }

    /**
     * Removes a user and its profiles along with their associated data.
     * @param userId the integer handle of the user.
     * @hide
     */
    @UnsupportedAppUsage
    @RequiresPermission(anyOf = {Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS})
    public boolean removeUser(@UserIdInt int userId) {
        try {
            return mService.removeUser(userId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Removes a user and its profiles along with their associated data.
     *
     * @param user the user that needs to be removed.
     * @return {@code true} if the user was successfully removed, {@code false} otherwise.
     * @throws IllegalArgumentException if {@code user} is {@code null}
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS})
    public boolean removeUser(@NonNull UserHandle user) {
        if (user == null) {
            throw new IllegalArgumentException("user cannot be null");
        }
        return removeUser(user.getIdentifier());
    }


    /**
     * Similar to {@link #removeUser(int)} except bypassing the checking of
     * {@link UserManager#DISALLOW_REMOVE_USER}
     * or {@link UserManager#DISALLOW_REMOVE_MANAGED_PROFILE}.
     *
     * @see {@link #removeUser(int)}
     * @hide
     */
    @RequiresPermission(anyOf = {Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS})
    public boolean removeUserEvenWhenDisallowed(@UserIdInt int userId) {
        try {
            return mService.removeUserEvenWhenDisallowed(userId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Immediately removes the user and its profiles or, if the user cannot be removed, such as
     * when the user is the current user, then set the user as ephemeral
     * so that it will be removed when it is stopped.
     *
     * @param overrideDevicePolicy when {@code true}, user is removed even if the caller has
     * the {@link #DISALLOW_REMOVE_USER} or {@link #DISALLOW_REMOVE_MANAGED_PROFILE} restriction
     *
     * @return the {@link RemoveResult} code: {@link #REMOVE_RESULT_REMOVED},
     * {@link #REMOVE_RESULT_DEFERRED}, {@link #REMOVE_RESULT_ALREADY_BEING_REMOVED},
     * {@link #REMOVE_RESULT_ERROR_USER_RESTRICTION}, {@link #REMOVE_RESULT_ERROR_USER_NOT_FOUND},
     * {@link #REMOVE_RESULT_ERROR_SYSTEM_USER},
     * {@link #REMOVE_RESULT_ERROR_MAIN_USER_PERMANENT_ADMIN}, or
     * {@link #REMOVE_RESULT_ERROR_UNKNOWN}. All error codes have negative values.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS})
    public @RemoveResult int removeUserWhenPossible(@NonNull UserHandle user,
            boolean overrideDevicePolicy) {
        try {
            return mService.removeUserWhenPossible(user.getIdentifier(), overrideDevicePolicy);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Check if {@link #removeUserWhenPossible} returned a success code which means that the user
     * has been removed or is slated for removal.
     *
     * @param result is {@link #RemoveResult} code return by {@link #removeUserWhenPossible}.
     * @return {@code true} if it is a success code.
     * @hide
     */
    @SystemApi
    public static boolean isRemoveResultSuccessful(@RemoveResult int result) {
        return result >= 0;
    }

    /**
     * Updates the user's name.
     *
     * @param userId the user's integer id
     * @param name the new name for the user
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public void setUserName(@UserIdInt int userId, String name) {
        try {
            mService.setUserName(userId, name);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Set the user as ephemeral or non-ephemeral.
     *
     * If the user was initially created as ephemeral then this
     * method has no effect and false is returned.
     *
     * @param userId the user's integer id
     * @param enableEphemeral true: change user state to ephemeral,
     *                        false: change user state to non-ephemeral
     * @return true: user now has the desired ephemeral state,
     *         false: desired user ephemeral state could not be set
     *
     * @hide
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.CREATE_USERS})
    public boolean setUserEphemeral(@UserIdInt int userId, boolean enableEphemeral) {
        try {
            return mService.setUserEphemeral(userId, enableEphemeral);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Updates the context user's name.
     *
     * @param name the new name for the user
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    @UserHandleAware
    public void setUserName(@Nullable String name) {
        setUserName(mUserId, name);
    }

    /**
     * Sets the user's photo.
     * @param userId the user for whom to change the photo.
     * @param icon the bitmap to set as the photo.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public void setUserIcon(@UserIdInt int userId, @NonNull Bitmap icon) {
        try {
            mService.setUserIcon(userId, icon);
        } catch (ServiceSpecificException e) {
            return;
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the context user's photo.
     *
     * @param icon the bitmap to set as the photo.
     *
     * @throws UserOperationException according to the function signature, but may not actually
     * throw it in practice. Catch RuntimeException instead.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    @UserHandleAware
    public void setUserIcon(@NonNull Bitmap icon) throws UserOperationException {
        setUserIcon(mUserId, icon);
    }

    /**
     * Returns a bitmap of the user's photo
     * @param userId the user whose photo we want to read.
     * @return a {@link Bitmap} of the user's photo, or null if there's no photo.
     * @see com.android.internal.util.UserIcons#getDefaultUserIcon for a default.
     * @hide
     */
    @UnsupportedAppUsage
    @RequiresPermission(anyOf = {android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.GET_ACCOUNTS_PRIVILEGED})
    public Bitmap getUserIcon(@UserIdInt int userId) {
        try {
            ParcelFileDescriptor fd = mService.getUserIcon(userId);
            if (fd != null) {
                try {
                    return BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor());
                } finally {
                    try {
                        fd.close();
                    } catch (IOException e) {
                    }
                }
            }
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
        return null;
    }

    /**
     * Returns a Bitmap for the context user's photo.
     *
     * @return a {@link Bitmap} of the user's photo, or null if there's no photo.
     * @see com.android.internal.util.UserIcons#getDefaultUserIcon for a default.
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.GET_ACCOUNTS_PRIVILEGED})
    @UserHandleAware
    public @Nullable Bitmap getUserIcon() {
        return getUserIcon(mUserId);
    }

    /**
     * Returns the maximum number of users that can be created on this device. A return value
     * of 1 means that it is a single user device.
     * @hide
     * @return a value greater than or equal to 1
     */
    @UnsupportedAppUsage
    public static int getMaxSupportedUsers() {
        // Don't allow multiple users on certain builds
        if (android.os.Build.ID.startsWith("JVP")) return 1;
        return Math.max(1, SystemProperties.getInt("fw.max_users",
                Resources.getSystem().getInteger(R.integer.config_multiuserMaximumUsers)));
    }

    /**
     * Returns true if the user switcher is enabled (regardless of whether there is anything
     * interesting for it to show).
     *
     * @return true if user switcher is enabled
     * @hide
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.CREATE_USERS // And INTERACT_ if diff profile group
    })
    @UserHandleAware
    public boolean isUserSwitcherEnabled() {
        return isUserSwitcherEnabled(true);
    }

    /**
     * Returns true if the user switcher should be shown.
     *
     * @param showEvenIfNotActionable value to return if the feature is enabled but there is nothing
     *                                actionable for the user to do anyway
     * @return true if user switcher should be shown.
     * @hide
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.CREATE_USERS // And INTERACT_ if diff profile group
    })
    @UserHandleAware
    public boolean isUserSwitcherEnabled(boolean showEvenIfNotActionable) {

        try {
            return mService.isUserSwitcherEnabled(showEvenIfNotActionable, mUserId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static boolean isDeviceInDemoMode(Context context) {
        return Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.DEVICE_DEMO_MODE, 0) > 0;
    }

    /**
     * Returns a serial number on this device for a given userId. User handles can be recycled
     * when deleting and creating users, but serial numbers are not reused until the device is wiped.
     * @param userId
     * @return a serial number associated with that user, or -1 if the userId is not valid.
     * @hide
     */
    @UnsupportedAppUsage
    public int getUserSerialNumber(@UserIdInt int userId) {
        try {
            return mService.getUserSerialNumber(userId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a userId on this device for a given user serial number. User handles can be
     * recycled when deleting and creating users, but serial numbers are not reused until the device
     * is wiped.
     * @param userSerialNumber
     * @return the userId associated with that user serial number, or -1 if the serial number
     * is not valid.
     * @hide
     */
    @UnsupportedAppUsage
    public @UserIdInt int getUserHandle(int userSerialNumber) {
        try {
            return mService.getUserHandle(userSerialNumber);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a {@link Bundle} containing any saved application restrictions for the context user,
     * for the given package name. Only an application with this package name can call this method.
     *
     * <p>The returned {@link Bundle} consists of key-value pairs, as defined by the application,
     * where the types of values may be:
     * <ul>
     * <li>{@code boolean}
     * <li>{@code int}
     * <li>{@code String} or {@code String[]}
     * <li>From {@link android.os.Build.VERSION_CODES#M}, {@code Bundle} or {@code Bundle[]}
     * </ul>
     *
     * <p>NOTE: The method performs disk I/O and shouldn't be called on the main thread
     *
     * @param packageName the package name of the calling application
     * @return a {@link Bundle} with the restrictions for that package, or an empty {@link Bundle}
     * if there are no saved restrictions.
     *
     * @see #KEY_RESTRICTIONS_PENDING
     *
     * <p>Starting from Android version {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE},
     * it is possible for there to be multiple managing apps on the device with the ability to set
     * restrictions, e.g. an Enterprise Device Policy Controller (DPC) and a Supervision admin.
     * This API will only to return the restrictions set by the DPCs. To retrieve restrictions
     * set by all managing apps, use
     * {@link android.content.RestrictionsManager#getApplicationRestrictionsPerAdmin} instead.
     *
     * @see DevicePolicyManager
     */
    @WorkerThread
    @UserHandleAware(enabledSinceTargetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public Bundle getApplicationRestrictions(String packageName) {
        try {
            return mService.getApplicationRestrictionsForUser(packageName,
                    getContextUserIfAppropriate());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * <p>Starting from Android version {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE},
     * it is possible for there to be multiple managing apps on the device with the ability to set
     * restrictions, e.g. an Enterprise Device Policy Controller (DPC) and a Supervision admin.
     * This API will only to return the restrictions set by the DPCs. To retrieve restrictions
     * set by all managing apps, use
     * {@link android.content.RestrictionsManager#getApplicationRestrictionsPerAdmin} instead.
     *
     * @hide
     */
    @WorkerThread
    public Bundle getApplicationRestrictions(String packageName, UserHandle user) {
        try {
            return mService.getApplicationRestrictionsForUser(packageName, user.getIdentifier());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @WorkerThread
    public void setApplicationRestrictions(String packageName, Bundle restrictions,
            UserHandle user) {
        try {
            mService.setApplicationRestrictions(packageName, restrictions, user.getIdentifier());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Sets a new challenge PIN for restrictions. This is only for use by pre-installed
     * apps and requires the MANAGE_USERS permission.
     * @param newPin the PIN to use for challenge dialogs.
     * @return Returns true if the challenge PIN was set successfully.
     * @deprecated The restrictions PIN functionality is no longer provided by the system.
     * This method is preserved for backwards compatibility reasons and always returns false.
     */
    @Deprecated
    public boolean setRestrictionsChallenge(String newPin) {
        return false;
    }

    /**
     * @hide
     * Set restrictions that should apply to any future guest user that's created.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public void setDefaultGuestRestrictions(Bundle restrictions) {
        try {
            mService.setDefaultGuestRestrictions(restrictions);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Gets the default guest restrictions.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public Bundle getDefaultGuestRestrictions() {
        try {
            return mService.getDefaultGuestRestrictions();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns creation time of the given user. The given user must be the calling user or
     * a profile associated with it.
     * @param userHandle user handle of the calling user or a profile associated with the
     *                   calling user.
     * @return creation time in milliseconds since Epoch time.
     */
    public long getUserCreationTime(UserHandle userHandle) {
        try {
            return mService.getUserCreationTime(userHandle.getIdentifier());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Checks if any uninitialized user has the specific seed account name and type.
     *
     * @param accountName The account name to check for
     * @param accountType The account type of the account to check for
     * @return whether the seed account was found
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public boolean someUserHasSeedAccount(String accountName, String accountType) {
        try {
            return mService.someUserHasSeedAccount(accountName, accountType);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Checks if any initialized or uninitialized user has the specific account name and type.
     *
     * @param accountName The account name to check for
     * @param accountType The account type of the account to check for
     * @return whether the account was found
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS})
    public boolean someUserHasAccount(
            @NonNull String accountName, @NonNull String accountType) {
        Objects.requireNonNull(accountName, "accountName must not be null");
        Objects.requireNonNull(accountType, "accountType must not be null");

        try {
            return mService.someUserHasAccount(accountName, accountType);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the user who should be in the foreground when boot completes. This should be called
     * during boot, and the provided user must be a full user (i.e. not a profile).
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS})
    public void setBootUser(@NonNull UserHandle bootUser) {
        try {
            mService.setBootUser(bootUser.getIdentifier());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the user who should be in the foreground when boot completes.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(anyOf = {Manifest.permission.MANAGE_USERS,
            Manifest.permission.CREATE_USERS})
    @SuppressWarnings("[AndroidFrameworkContextUserId]")
    public @NonNull UserHandle getBootUser() {
        try {
            return UserHandle.of(mService.getBootUser());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /* Cache key for anything that assumes that userIds cannot be re-used without rebooting. */
    private static final String CACHE_KEY_STATIC_USER_PROPERTIES = "cache_key.static_user_props";

    private final PropertyInvalidatedCache<Integer, String> mProfileTypeCache =
            new PropertyInvalidatedCache<Integer, String>(32, CACHE_KEY_STATIC_USER_PROPERTIES) {
                @Override
                public String recompute(Integer query) {
                    try {
                        // Will be null (and not cached) if invalid user; otherwise cache the type.
                        String profileType = mService.getProfileType(query);
                        if (profileType != null) profileType = profileType.intern();
                        return profileType;
                    } catch (RemoteException re) {
                        throw re.rethrowFromSystemServer();
                    }
                }
                @Override
                public boolean bypass(Integer query) {
                    return query < 0;
                }
            };

    /** @hide */
    public static final void invalidateStaticUserProperties() {
        PropertyInvalidatedCache.invalidateCache(CACHE_KEY_STATIC_USER_PROPERTIES);
    }

    /* Cache key for UserProperties object. */
    private static final String CACHE_KEY_USER_PROPERTIES = "cache_key.user_properties";

    // TODO: It would be better to somehow have this as static, so that it can work cross-context.
    private final PropertyInvalidatedCache<Integer, UserProperties> mUserPropertiesCache =
            new PropertyInvalidatedCache<Integer, UserProperties>(16, CACHE_KEY_USER_PROPERTIES) {
                @Override
                public UserProperties recompute(Integer userId) {
                    try {
                        // If the userId doesn't exist, this will throw rather than cache garbage.
                        return mService.getUserPropertiesCopy(userId);
                    } catch (RemoteException re) {
                        throw re.rethrowFromSystemServer();
                    }
                }
                @Override
                public boolean bypass(Integer query) {
                    return query < 0;
                }
            };

    /** @hide */
    public static final void invalidateUserPropertiesCache() {
        PropertyInvalidatedCache.invalidateCache(CACHE_KEY_USER_PROPERTIES);
    }

    /**
     * @hide
     * User that enforces a restriction.
     *
     * @see #getUserRestrictionSources(String, UserHandle)
     */
    @SystemApi
    public static final class EnforcingUser implements Parcelable {
        private final @UserIdInt int userId;
        private final @UserRestrictionSource int userRestrictionSource;

        /**
         * @hide
         */
        public EnforcingUser(
                @UserIdInt int userId, @UserRestrictionSource int userRestrictionSource) {
            this.userId = userId;
            this.userRestrictionSource = userRestrictionSource;
        }

        private EnforcingUser(Parcel in) {
            userId = in.readInt();
            userRestrictionSource = in.readInt();
        }

        public static final @android.annotation.NonNull Creator<EnforcingUser> CREATOR = new Creator<EnforcingUser>() {
            @Override
            public EnforcingUser createFromParcel(Parcel in) {
                return new EnforcingUser(in);
            }

            @Override
            public EnforcingUser[] newArray(int size) {
                return new EnforcingUser[size];
            }
        };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(userId);
            dest.writeInt(userRestrictionSource);
        }

        /**
         * Returns an id of the enforcing user.
         *
         * <p> Will be UserHandle.USER_NULL when restriction is set by the system.
         */
        public UserHandle getUserHandle() {
            return UserHandle.of(userId);
        }

        /**
         * Returns the status of the enforcing user.
         *
         * <p> One of {@link #RESTRICTION_SOURCE_SYSTEM},
         * {@link #RESTRICTION_SOURCE_DEVICE_OWNER} and
         * {@link #RESTRICTION_SOURCE_PROFILE_OWNER}
         */
        public @UserRestrictionSource int getUserRestrictionSource() {
            return userRestrictionSource;
        }
    }
}
