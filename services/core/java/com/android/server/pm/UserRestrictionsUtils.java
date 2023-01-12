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

package com.android.server.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.BundleUtils;
import com.android.server.LocalServices;

import com.google.android.collect.Sets;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Utility methods for user restrictions.
 *
 * <p>See {@link UserManagerService} for the method suffixes.
 */
public class UserRestrictionsUtils {
    private static final String TAG = "UserRestrictionsUtils";

    private UserRestrictionsUtils() {
    }

    private static Set<String> newSetWithUniqueCheck(String[] strings) {
        final Set<String> ret = Sets.newArraySet(strings);

        // Make sure there's no overlap.
        Preconditions.checkState(ret.size() == strings.length);
        return ret;
    }

    public static final Set<String> USER_RESTRICTIONS = newSetWithUniqueCheck(new String[] {
            UserManager.DISALLOW_CONFIG_WIFI,
            UserManager.DISALLOW_CONFIG_LOCALE,
            UserManager.DISALLOW_MODIFY_ACCOUNTS,
            UserManager.DISALLOW_INSTALL_APPS,
            UserManager.DISALLOW_UNINSTALL_APPS,
            UserManager.DISALLOW_SHARE_LOCATION,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
            UserManager.DISALLOW_CONFIG_BLUETOOTH,
            UserManager.DISALLOW_BLUETOOTH,
            UserManager.DISALLOW_BLUETOOTH_SHARING,
            UserManager.DISALLOW_USB_FILE_TRANSFER,
            UserManager.DISALLOW_CONFIG_CREDENTIALS,
            UserManager.DISALLOW_REMOVE_USER,
            UserManager.DISALLOW_REMOVE_MANAGED_PROFILE,
            UserManager.DISALLOW_DEBUGGING_FEATURES,
            UserManager.DISALLOW_CONFIG_VPN,
            UserManager.DISALLOW_CONFIG_DATE_TIME,
            UserManager.DISALLOW_CONFIG_TETHERING,
            UserManager.DISALLOW_NETWORK_RESET,
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_ADD_MANAGED_PROFILE,
            UserManager.DISALLOW_ADD_CLONE_PROFILE,
            UserManager.ENSURE_VERIFY_APPS,
            UserManager.DISALLOW_CONFIG_CELL_BROADCASTS,
            UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS,
            UserManager.DISALLOW_APPS_CONTROL,
            UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
            UserManager.DISALLOW_UNMUTE_MICROPHONE,
            UserManager.DISALLOW_ADJUST_VOLUME,
            UserManager.DISALLOW_OUTGOING_CALLS,
            UserManager.DISALLOW_SMS,
            UserManager.DISALLOW_FUN,
            UserManager.DISALLOW_CREATE_WINDOWS,
            UserManager.DISALLOW_SYSTEM_ERROR_DIALOGS,
            UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE,
            UserManager.DISALLOW_OUTGOING_BEAM,
            UserManager.DISALLOW_WALLPAPER,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.ALLOW_PARENT_PROFILE_APP_LINKING,
            UserManager.DISALLOW_RECORD_AUDIO,
            UserManager.DISALLOW_CAMERA,
            UserManager.DISALLOW_RUN_IN_BACKGROUND,
            UserManager.DISALLOW_DATA_ROAMING,
            UserManager.DISALLOW_SET_USER_ICON,
            UserManager.DISALLOW_SET_WALLPAPER,
            UserManager.DISALLOW_OEM_UNLOCK,
            UserManager.DISALLOW_UNMUTE_DEVICE,
            UserManager.DISALLOW_AUTOFILL,
            UserManager.DISALLOW_CONTENT_CAPTURE,
            UserManager.DISALLOW_CONTENT_SUGGESTIONS,
            UserManager.DISALLOW_USER_SWITCH,
            UserManager.DISALLOW_UNIFIED_PASSWORD,
            UserManager.DISALLOW_CONFIG_LOCATION,
            UserManager.DISALLOW_AIRPLANE_MODE,
            UserManager.DISALLOW_CONFIG_BRIGHTNESS,
            UserManager.DISALLOW_SHARE_INTO_MANAGED_PROFILE,
            UserManager.DISALLOW_AMBIENT_DISPLAY,
            UserManager.DISALLOW_CONFIG_SCREEN_TIMEOUT,
            UserManager.DISALLOW_PRINTING,
            UserManager.DISALLOW_CONFIG_PRIVATE_DNS,
            UserManager.DISALLOW_MICROPHONE_TOGGLE,
            UserManager.DISALLOW_CAMERA_TOGGLE,
            UserManager.DISALLOW_CHANGE_WIFI_STATE,
            UserManager.DISALLOW_WIFI_TETHERING,
            UserManager.DISALLOW_SHARING_ADMIN_CONFIGURED_WIFI,
            UserManager.DISALLOW_WIFI_DIRECT,
            UserManager.DISALLOW_ADD_WIFI_CONFIG,
            UserManager.DISALLOW_CELLULAR_2G,
            UserManager.DISALLOW_ULTRA_WIDEBAND_RADIO,
            UserManager.DISALLOW_CONFIG_DEFAULT_APPS
    });

    public static final Set<String> DEPRECATED_USER_RESTRICTIONS = Sets.newArraySet(
            UserManager.DISALLOW_ADD_MANAGED_PROFILE,
            UserManager.DISALLOW_REMOVE_MANAGED_PROFILE
    );

    /**
     * Set of user restriction which we don't want to persist.
     */
    private static final Set<String> NON_PERSIST_USER_RESTRICTIONS = Sets.newArraySet(
            UserManager.DISALLOW_RECORD_AUDIO
    );

    /**
     * User restrictions that cannot be set by profile owners of secondary users. When set by DO
     * they will be applied to all users.
     */
    private static final Set<String> PRIMARY_USER_ONLY_RESTRICTIONS = Sets.newArraySet(
            UserManager.DISALLOW_BLUETOOTH,
            UserManager.DISALLOW_USB_FILE_TRANSFER,
            UserManager.DISALLOW_CONFIG_TETHERING,
            UserManager.DISALLOW_NETWORK_RESET,
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_CONFIG_CELL_BROADCASTS,
            UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS,
            UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
            UserManager.DISALLOW_SMS,
            UserManager.DISALLOW_FUN,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_CREATE_WINDOWS,
            UserManager.DISALLOW_DATA_ROAMING,
            UserManager.DISALLOW_AIRPLANE_MODE
    );

    /**
     * User restrictions that cannot be set by profile owners. Applied to all users.
     */
    private static final Set<String> DEVICE_OWNER_ONLY_RESTRICTIONS = Sets.newArraySet(
            UserManager.DISALLOW_USER_SWITCH,
            UserManager.DISALLOW_CONFIG_PRIVATE_DNS,
            UserManager.DISALLOW_MICROPHONE_TOGGLE,
            UserManager.DISALLOW_CAMERA_TOGGLE,
            UserManager.DISALLOW_CHANGE_WIFI_STATE,
            UserManager.DISALLOW_WIFI_TETHERING,
            UserManager.DISALLOW_WIFI_DIRECT,
            UserManager.DISALLOW_ADD_WIFI_CONFIG,
            UserManager.DISALLOW_CELLULAR_2G,
            UserManager.DISALLOW_ULTRA_WIDEBAND_RADIO
    );

    /**
     * User restrictions that can't be changed by device owner or profile owner.
     */
    private static final Set<String> IMMUTABLE_BY_OWNERS = Sets.newArraySet(
            UserManager.DISALLOW_RECORD_AUDIO,
            UserManager.DISALLOW_WALLPAPER,
            UserManager.DISALLOW_OEM_UNLOCK
    );

    /**
     * Special user restrictions that can be applied to a user as well as to all users globally,
     * depending on callers.  When device owner sets them, they'll be applied to all users.
     */
    private static final Set<String> GLOBAL_RESTRICTIONS = Sets.newArraySet(
            UserManager.DISALLOW_ADJUST_VOLUME,
            UserManager.DISALLOW_BLUETOOTH_SHARING,
            UserManager.DISALLOW_CONFIG_DATE_TIME,
            UserManager.DISALLOW_SYSTEM_ERROR_DIALOGS,
            UserManager.DISALLOW_RUN_IN_BACKGROUND,
            UserManager.DISALLOW_UNMUTE_MICROPHONE,
            UserManager.DISALLOW_UNMUTE_DEVICE,
            UserManager.DISALLOW_CAMERA
    );

    /**
     * Special user restrictions that profile owner of an organization-owned managed profile can
     * set on the parent profile instance to apply them globally.
     */
    private static final Set<String> PROFILE_OWNER_ORGANIZATION_OWNED_GLOBAL_RESTRICTIONS =
            Sets.newArraySet(
                    UserManager.DISALLOW_AIRPLANE_MODE,
                    UserManager.DISALLOW_CONFIG_DATE_TIME,
                    UserManager.DISALLOW_CONFIG_PRIVATE_DNS,
                    UserManager.DISALLOW_CHANGE_WIFI_STATE,
                    UserManager.DISALLOW_WIFI_TETHERING,
                    UserManager.DISALLOW_WIFI_DIRECT,
                    UserManager.DISALLOW_ADD_WIFI_CONFIG,
                    UserManager.DISALLOW_CELLULAR_2G,
                    UserManager.DISALLOW_ULTRA_WIDEBAND_RADIO
    );

    /**
     * Special user restrictions that profile owner of an organization-owned managed profile can
     * set on the parent profile instance to apply them on the personal profile.
     */
    private static final Set<String> PROFILE_OWNER_ORGANIZATION_OWNED_LOCAL_RESTRICTIONS =
            Sets.newArraySet(
                    UserManager.DISALLOW_CONFIG_BLUETOOTH,
                    UserManager.DISALLOW_CONFIG_LOCATION,
                    UserManager.DISALLOW_CONFIG_WIFI,
                    UserManager.DISALLOW_CONTENT_CAPTURE,
                    UserManager.DISALLOW_CONTENT_SUGGESTIONS,
                    UserManager.DISALLOW_DEBUGGING_FEATURES,
                    UserManager.DISALLOW_SHARE_LOCATION,
                    UserManager.DISALLOW_OUTGOING_CALLS,
                    UserManager.DISALLOW_CAMERA,
                    UserManager.DISALLOW_BLUETOOTH,
                    UserManager.DISALLOW_BLUETOOTH_SHARING,
                    UserManager.DISALLOW_CONFIG_CELL_BROADCASTS,
                    UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS,
                    UserManager.DISALLOW_CONFIG_TETHERING,
                    UserManager.DISALLOW_DATA_ROAMING,
                    UserManager.DISALLOW_SAFE_BOOT,
                    UserManager.DISALLOW_SMS,
                    UserManager.DISALLOW_USB_FILE_TRANSFER,
                    UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
                    UserManager.DISALLOW_UNMUTE_MICROPHONE
    );

    /**
     * User restrictions that default to {@code true} for managed profile owners.
     *
     * NB: {@link UserManager#DISALLOW_INSTALL_UNKNOWN_SOURCES} is also set by default but it is
     * not set to existing profile owners unless they used to have INSTALL_NON_MARKET_APPS disabled
     * in settings. So it is handled separately.
     */
    private static final Set<String> DEFAULT_ENABLED_FOR_MANAGED_PROFILES = Sets.newArraySet(
            UserManager.DISALLOW_BLUETOOTH_SHARING
    );

    /**
     * Special user restrictions that are always applied to all users no matter who sets them.
     */
    private static final Set<String> PROFILE_GLOBAL_RESTRICTIONS = Sets.newArraySet(
            UserManager.ENSURE_VERIFY_APPS,
            UserManager.DISALLOW_AIRPLANE_MODE,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY
    );

    /**
     * User restrictions available to a device owner whose type is
     * {@link android.app.admin.DevicePolicyManager#DEVICE_OWNER_TYPE_FINANCED}.
     */
    private static final Set<String> FINANCED_DEVICE_OWNER_RESTRICTIONS = Sets.newArraySet(
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_DEBUGGING_FEATURES,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_CONFIG_DATE_TIME,
            UserManager.DISALLOW_OUTGOING_CALLS
    );

    /**
     * Returns whether the given restriction name is valid (and logs it if it isn't).
     */
    public static boolean isValidRestriction(@NonNull String restriction) {
        if (!USER_RESTRICTIONS.contains(restriction)) {
            // Log this, with severity depending on the source.
            final int uid = Binder.getCallingUid();
            String[] pkgs = null;
            try {
                pkgs = AppGlobals.getPackageManager().getPackagesForUid(uid);
            } catch (RemoteException e) {
                // Ignore
            }
            StringBuilder msg = new StringBuilder("Unknown restriction queried by uid ");
            msg.append(uid);
            if (pkgs != null && pkgs.length > 0) {
                msg.append(" (");
                msg.append(pkgs[0]);
                if (pkgs.length > 1) {
                    msg.append(" et al");
                }
                msg.append(")");
            }
            msg.append(": ");
            msg.append(restriction);
            if (restriction != null && isSystemApp(uid, pkgs)) {
                Slog.wtf(TAG, msg.toString());
            } else {
                Slog.e(TAG, msg.toString());
            }
            return false;
        }
        return true;
    }

    /** Returns whether the given uid (or corresponding packageList) is for a System app. */
    private static boolean isSystemApp(int uid, String[] packageList) {
        if (UserHandle.isCore(uid)) {
            return true;
        }
        if (packageList == null) {
            return false;
        }
        final IPackageManager pm = AppGlobals.getPackageManager();
        for (int i = 0; i < packageList.length; i++) {
            try {
                final int flags = PackageManager.MATCH_UNINSTALLED_PACKAGES
                        | PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
                final ApplicationInfo appInfo =
                        pm.getApplicationInfo(packageList[i], flags, UserHandle.getUserId(uid));
                if (appInfo != null && appInfo.isSystemApp()) {
                    return true;
                }
            } catch (RemoteException e) {
                // Ignore
            }
        }
        return false;
    }

    public static void writeRestrictions(@NonNull XmlSerializer serializer,
            @Nullable Bundle restrictions, @NonNull String tag) throws IOException {
        writeRestrictions(XmlUtils.makeTyped(serializer), restrictions, tag);
    }

    public static void writeRestrictions(@NonNull TypedXmlSerializer serializer,
            @Nullable Bundle restrictions, @NonNull String tag) throws IOException {
        if (restrictions == null) {
            return;
        }

        serializer.startTag(null, tag);
        for (String key : restrictions.keySet()) {
            if (NON_PERSIST_USER_RESTRICTIONS.contains(key)) {
                continue; // Don't persist.
            }
            if (USER_RESTRICTIONS.contains(key)) {
                if (restrictions.getBoolean(key)) {
                    serializer.attributeBoolean(null, key, true);
                }
                continue;
            }
            Log.w(TAG, "Unknown user restriction detected: " + key);
        }
        serializer.endTag(null, tag);
    }

    public static void readRestrictions(XmlPullParser parser, Bundle restrictions) {
        readRestrictions(XmlUtils.makeTyped(parser), restrictions);
    }

    public static void readRestrictions(TypedXmlPullParser parser, Bundle restrictions) {
        restrictions.clear();
        for (String key : USER_RESTRICTIONS) {
            final boolean value = parser.getAttributeBoolean(null, key, false);
            if (value) {
                restrictions.putBoolean(key, true);
            }
        }
    }

    public static Bundle readRestrictions(XmlPullParser parser) {
        return readRestrictions(XmlUtils.makeTyped(parser));
    }

    public static Bundle readRestrictions(TypedXmlPullParser parser) {
        final Bundle result = new Bundle();
        readRestrictions(parser, result);
        return result;
    }

    /**
     * @return {@code in} itself when it's not null, or an empty bundle (which can writable).
     */
    public static Bundle nonNull(@Nullable Bundle in) {
        return in != null ? in : new Bundle();
    }

    /**
     * Returns {@code true} if given bundle is not null and contains {@code true} for a given
     * restriction.
     */
    public static boolean contains(@Nullable Bundle in, String restriction) {
        return in != null && in.getBoolean(restriction);
    }

    public static void merge(@NonNull Bundle dest, @Nullable Bundle in) {
        Objects.requireNonNull(dest);
        Preconditions.checkArgument(dest != in);
        if (in == null) {
            return;
        }
        for (String key : in.keySet()) {
            if (in.getBoolean(key, false)) {
                dest.putBoolean(key, true);
            }
        }
    }

    /**
     * @return true if a restriction is settable by device owner.
     */
    public static boolean canDeviceOwnerChange(String restriction) {
        return !IMMUTABLE_BY_OWNERS.contains(restriction);
    }

    /**
     * @return true if a restriction is settable by profile owner.  Note it takes a user ID because
     * some restrictions can be changed by PO only when it's running on the system user.
     */
    public static boolean canProfileOwnerChange(String restriction, int userId) {
        return !IMMUTABLE_BY_OWNERS.contains(restriction)
                && !DEVICE_OWNER_ONLY_RESTRICTIONS.contains(restriction)
                && !(userId != UserHandle.USER_SYSTEM
                    && PRIMARY_USER_ONLY_RESTRICTIONS.contains(restriction));
    }

    /**
     * @return true if a restriction is settable by profile owner of an organization owned device.
     */
    public static boolean canProfileOwnerOfOrganizationOwnedDeviceChange(String restriction) {
        return PROFILE_OWNER_ORGANIZATION_OWNED_GLOBAL_RESTRICTIONS.contains(restriction)
                || PROFILE_OWNER_ORGANIZATION_OWNED_LOCAL_RESTRICTIONS.contains(restriction);
    }

    /**
     * Returns the user restrictions that default to {@code true} for managed profile owners.
     */
    public static @NonNull Set<String> getDefaultEnabledForManagedProfiles() {
        return DEFAULT_ENABLED_FOR_MANAGED_PROFILES;
    }

    /**
     * @return {@code true} only if the restriction is allowed for financed devices and can be set
     * by a device owner. Otherwise, {@code false} would be returned.
     */
    public static boolean canFinancedDeviceOwnerChange(String restriction) {
        return FINANCED_DEVICE_OWNER_RESTRICTIONS.contains(restriction)
                && canDeviceOwnerChange(restriction);
    }

    /**
     * Whether given user restriction should be enforced globally.
     */
    public static boolean isGlobal(@UserManagerInternal.OwnerType int restrictionOwnerType,
            String key) {
        return ((restrictionOwnerType == UserManagerInternal.OWNER_TYPE_DEVICE_OWNER) && (
                PRIMARY_USER_ONLY_RESTRICTIONS.contains(key) || GLOBAL_RESTRICTIONS.contains(key)))
                || ((restrictionOwnerType
                == UserManagerInternal.OWNER_TYPE_PROFILE_OWNER_OF_ORGANIZATION_OWNED_DEVICE)
                && PROFILE_OWNER_ORGANIZATION_OWNED_GLOBAL_RESTRICTIONS.contains(key))
                || PROFILE_GLOBAL_RESTRICTIONS.contains(key)
                || DEVICE_OWNER_ONLY_RESTRICTIONS.contains(key);
    }

    /**
     * Whether given user restriction should be enforced locally.
     */
    public static boolean isLocal(@UserManagerInternal.OwnerType int restrictionOwnerType,
            String key) {
        return !isGlobal(restrictionOwnerType, key);
    }

    /**
     * @return true if two Bundles contain the same user restriction.
     * A null bundle and an empty bundle are considered to be equal.
     */
    public static boolean areEqual(@Nullable Bundle a, @Nullable Bundle b) {
        if (a == b) {
            return true;
        }
        if (BundleUtils.isEmpty(a)) {
            return BundleUtils.isEmpty(b);
        }
        if (BundleUtils.isEmpty(b)) {
            return false;
        }
        for (String key : a.keySet()) {
            if (a.getBoolean(key) != b.getBoolean(key)) {
                return false;
            }
        }
        for (String key : b.keySet()) {
            if (a.getBoolean(key) != b.getBoolean(key)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Takes a new use restriction set and the previous set, and apply the restrictions that have
     * changed.
     *
     * <p>Note this method is called by {@link UserManagerService} without holding any locks.
     */
    public static void applyUserRestrictions(Context context, int userId,
            Bundle newRestrictions, Bundle prevRestrictions) {
        for (String key : USER_RESTRICTIONS) {
            final boolean newValue = newRestrictions.getBoolean(key);
            final boolean prevValue = prevRestrictions.getBoolean(key);

            if (newValue != prevValue) {
                applyUserRestriction(context, userId, key, newValue);
            }
        }
    }

    /**
     * Apply each user restriction.
     *
     * <p>See also {@link #isSettingRestrictedForUser()},
     * which should be in sync with this method.
     */
    private static void applyUserRestriction(Context context, int userId, String key,
            boolean newValue) {
        if (UserManagerService.DBG) {
            Log.d(TAG, "Applying user restriction: userId=" + userId
                    + " key=" + key + " value=" + newValue);
        }
        // When certain restrictions are cleared, we don't update the system settings,
        // because these settings are changeable on the Settings UI and we don't know the original
        // value -- for example LOCATION_MODE might have been off already when the restriction was
        // set, and in that case even if the restriction is lifted, changing it to ON would be
        // wrong.  So just don't do anything in such a case.  If the user hopes to enable location
        // later, they can do it on the Settings UI.
        // WARNING: Remember that Settings.Global and Settings.Secure are changeable via adb.
        // To prevent this from happening for a given user restriction, you have to add a check to
        // SettingsProvider.isGlobalOrSecureSettingRestrictedForUser.

        final ContentResolver cr = context.getContentResolver();
        final long id = Binder.clearCallingIdentity();
        try {
            switch (key) {
                case UserManager.DISALLOW_DATA_ROAMING:
                    if (newValue) {
                        // DISALLOW_DATA_ROAMING user restriction is set.

                        // Multi sim device.
                        SubscriptionManager subscriptionManager = context
                                .getSystemService(SubscriptionManager.class);
                        final List<SubscriptionInfo> subscriptionInfoList =
                            subscriptionManager.getActiveSubscriptionInfoList();
                        if (subscriptionInfoList != null) {
                            for (SubscriptionInfo subInfo : subscriptionInfoList) {
                                android.provider.Settings.Global.putStringForUser(cr,
                                    android.provider.Settings.Global.DATA_ROAMING
                                    + subInfo.getSubscriptionId(), "0", userId);
                            }
                        }

                        // Single sim device.
                        android.provider.Settings.Global.putStringForUser(cr,
                            android.provider.Settings.Global.DATA_ROAMING, "0", userId);
                    }
                    break;
                case UserManager.DISALLOW_SHARE_LOCATION:
                    if (newValue) {
                        android.provider.Settings.Secure.putIntForUser(cr,
                                android.provider.Settings.Secure.LOCATION_MODE,
                                android.provider.Settings.Secure.LOCATION_MODE_OFF,
                                userId);
                    }
                    break;
                case UserManager.DISALLOW_DEBUGGING_FEATURES:
                    if (newValue) {
                        // Only disable adb if changing for system user, since it is global
                        // TODO: should this be admin user?
                        if (userId == UserHandle.USER_SYSTEM) {
                            android.provider.Settings.Global.putStringForUser(cr,
                                    android.provider.Settings.Global.ADB_ENABLED, "0",
                                    userId);
                            android.provider.Settings.Global.putStringForUser(cr,
                                    android.provider.Settings.Global.ADB_WIFI_ENABLED, "0",
                                    userId);
                        }
                    }
                    break;
                case UserManager.ENSURE_VERIFY_APPS:
                    if (newValue) {
                        android.provider.Settings.Global.putStringForUser(
                                context.getContentResolver(),
                                android.provider.Settings.Global.PACKAGE_VERIFIER_INCLUDE_ADB, "1",
                                userId);
                    }
                    break;
                case UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY:
                    setInstallMarketAppsRestriction(cr, userId, getNewUserRestrictionSetting(
                            context, userId, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                            newValue));
                    break;
                case UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES:
                    // Since Android O, the secure setting is not available to be changed by the
                    // user. Hence, when the restriction is cleared, we need to reset the state of
                    // the setting to its default value which is now 1.
                    setInstallMarketAppsRestriction(cr, userId, getNewUserRestrictionSetting(
                            context, userId, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
                            newValue));
                    break;
                case UserManager.DISALLOW_RUN_IN_BACKGROUND:
                    if (newValue) {
                        int currentUser = ActivityManager.getCurrentUser();
                        if (currentUser != userId && userId != UserHandle.USER_SYSTEM) {
                            try {
                                ActivityManager.getService().stopUser(userId, false, null);
                            } catch (RemoteException e) {
                                throw e.rethrowAsRuntimeException();
                            }
                        }
                    }
                    break;
                case UserManager.DISALLOW_SAFE_BOOT:
                    // Unlike with the other restrictions, we want to propagate the new value to
                    // the system settings even if it is false. The other restrictions modify
                    // settings which could be manually changed by the user from the Settings app
                    // after the policies enforcing these restrictions have been revoked, so we
                    // leave re-setting of those settings to the user.
                    android.provider.Settings.Global.putInt(
                            context.getContentResolver(),
                            android.provider.Settings.Global.SAFE_BOOT_DISALLOWED,
                            newValue ? 1 : 0);
                    break;
                case UserManager.DISALLOW_AIRPLANE_MODE:
                    if (newValue) {
                        final boolean airplaneMode = Settings.Global.getInt(
                                context.getContentResolver(),
                                Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
                        if (airplaneMode) {
                            android.provider.Settings.Global.putInt(
                                    context.getContentResolver(),
                                    android.provider.Settings.Global.AIRPLANE_MODE_ON, 0);
                            // Post the intent.
                            Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
                            intent.putExtra("state", false);
                            context.sendBroadcastAsUser(intent, UserHandle.ALL);
                        }
                    }
                    break;
                case UserManager.DISALLOW_AMBIENT_DISPLAY:
                    if (newValue) {
                        final AmbientDisplayConfiguration config =
                                new AmbientDisplayConfiguration(context);
                        config.disableDozeSettings(userId);
                    }
                    break;
                case UserManager.DISALLOW_APPS_CONTROL:
                    // Intentional fall-through
                case UserManager.DISALLOW_UNINSTALL_APPS:
                    final PackageManagerInternal pmi = LocalServices.getService(
                            PackageManagerInternal.class);
                    pmi.removeAllNonSystemPackageSuspensions(userId);
                    pmi.removeAllDistractingPackageRestrictions(userId);
                    pmi.flushPackageRestrictions(userId);
                    break;
            }
        } finally {
            Binder.restoreCallingIdentity(id);
        }
    }

    public static boolean isSettingRestrictedForUser(Context context, @NonNull String setting,
            int userId, String value, int callingUid) {
        Objects.requireNonNull(setting);
        final UserManager mUserManager = context.getSystemService(UserManager.class);
        String restriction;
        boolean checkAllUser = false;
        switch (setting) {
            case android.provider.Settings.Secure.LOCATION_MODE:
                if (mUserManager.hasUserRestriction(
                        UserManager.DISALLOW_CONFIG_LOCATION, UserHandle.of(userId))
                        && callingUid != Process.SYSTEM_UID) {
                    return true;
                } else if (String.valueOf(Settings.Secure.LOCATION_MODE_OFF).equals(value)) {
                    return false;
                }
                restriction = UserManager.DISALLOW_SHARE_LOCATION;
                break;

            case android.provider.Settings.Secure.INSTALL_NON_MARKET_APPS:
                if ("0".equals(value)) {
                    return false;
                }
                restriction = UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES;
                break;

            case android.provider.Settings.Global.ADB_ENABLED:
            case android.provider.Settings.Global.ADB_WIFI_ENABLED:
                if ("0".equals(value)) {
                    return false;
                }
                restriction = UserManager.DISALLOW_DEBUGGING_FEATURES;
                break;

            case android.provider.Settings.Global.PACKAGE_VERIFIER_INCLUDE_ADB:
                if ("1".equals(value)) {
                    return false;
                }
                restriction = UserManager.ENSURE_VERIFY_APPS;
                break;

            case android.provider.Settings.Global.PREFERRED_NETWORK_MODE:
                restriction = UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS;
                break;

            case android.provider.Settings.Secure.ALWAYS_ON_VPN_APP:
            case android.provider.Settings.Secure.ALWAYS_ON_VPN_LOCKDOWN:
            case android.provider.Settings.Secure.ALWAYS_ON_VPN_LOCKDOWN_WHITELIST:
                // Allowlist system uid (ConnectivityService) and root uid to change always-on vpn
                final int appId = UserHandle.getAppId(callingUid);
                if (appId == Process.SYSTEM_UID || appId == Process.ROOT_UID) {
                    return false;
                }
                restriction = UserManager.DISALLOW_CONFIG_VPN;
                break;

            case android.provider.Settings.Global.SAFE_BOOT_DISALLOWED:
                if ("1".equals(value)) {
                    return false;
                }
                restriction = UserManager.DISALLOW_SAFE_BOOT;
                break;

            case android.provider.Settings.Global.AIRPLANE_MODE_ON:
                if ("0".equals(value)) {
                    return false;
                }
                restriction = UserManager.DISALLOW_AIRPLANE_MODE;
                break;

            case android.provider.Settings.Secure.DOZE_ENABLED:
            case android.provider.Settings.Secure.DOZE_ALWAYS_ON:
            case android.provider.Settings.Secure.DOZE_PICK_UP_GESTURE:
            case android.provider.Settings.Secure.DOZE_PULSE_ON_LONG_PRESS:
            case android.provider.Settings.Secure.DOZE_DOUBLE_TAP_GESTURE:
                if ("0".equals(value)) {
                    return false;
                }
                restriction = UserManager.DISALLOW_AMBIENT_DISPLAY;
                break;

            case android.provider.Settings.System.SCREEN_BRIGHTNESS:
            case android.provider.Settings.System.SCREEN_BRIGHTNESS_FLOAT:
            case android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE:
                if (callingUid == Process.SYSTEM_UID) {
                    return false;
                }
                restriction = UserManager.DISALLOW_CONFIG_BRIGHTNESS;
                break;

            case android.provider.Settings.Global.AUTO_TIME:
            case android.provider.Settings.Global.AUTO_TIME_ZONE:
                if (callingUid == Process.SYSTEM_UID) {
                    return false;
                }
                restriction = UserManager.DISALLOW_CONFIG_DATE_TIME;
                break;

            case android.provider.Settings.System.SCREEN_OFF_TIMEOUT:
                if (callingUid == Process.SYSTEM_UID) {
                    return false;
                }
                restriction = UserManager.DISALLOW_CONFIG_SCREEN_TIMEOUT;
                break;

            case android.provider.Settings.Global.PRIVATE_DNS_MODE:
            case android.provider.Settings.Global.PRIVATE_DNS_SPECIFIER:
                if (callingUid == Process.SYSTEM_UID) {
                    return false;
                }
                restriction = UserManager.DISALLOW_CONFIG_PRIVATE_DNS;
                break;
            default:
                if (setting.startsWith(Settings.Global.DATA_ROAMING)) {
                    if ("0".equals(value)) {
                        return false;
                    }
                    restriction = UserManager.DISALLOW_DATA_ROAMING;
                    break;
                }
                return false;
        }

        if (checkAllUser) {
            return mUserManager.hasUserRestrictionOnAnyUser(restriction);
        } else {
            return mUserManager.hasUserRestriction(restriction, UserHandle.of(userId));
        }
    }

    public static void dumpRestrictions(PrintWriter pw, String prefix, Bundle restrictions) {
        boolean noneSet = true;
        if (restrictions != null) {
            for (String key : restrictions.keySet()) {
                if (restrictions.getBoolean(key, false)) {
                    pw.println(prefix + key);
                    noneSet = false;
                }
            }
            if (noneSet) {
                pw.println(prefix + "none");
            }
        } else {
            pw.println(prefix + "null");
        }
    }

    /**
     * Moves a particular restriction from one array of restrictions sets to a restriction set,
     * e.g. for all users.
     */
    public static void moveRestriction(String restrictionKey,
            SparseArray<RestrictionsSet> sourceRestrictionsSets,
            RestrictionsSet destRestrictionSet) {
        for (int i = 0; i < sourceRestrictionsSets.size(); i++) {
            final RestrictionsSet sourceRestrictionsSet = sourceRestrictionsSets.valueAt(i);
            sourceRestrictionsSet.moveRestriction(destRestrictionSet, restrictionKey);
        }
    }

    /**
     * Returns whether restrictions differ between two bundles.
     * @param oldRestrictions old bundle of restrictions.
     * @param newRestrictions new bundle of restrictions
     * @param restrictions restrictions of interest, if empty, all restrictions are checked.
     */
    public static boolean restrictionsChanged(Bundle oldRestrictions, Bundle newRestrictions,
            String... restrictions) {
        if (restrictions.length == 0) {
            return areEqual(oldRestrictions, newRestrictions);
        }
        for (final String restriction : restrictions) {
            if (oldRestrictions.getBoolean(restriction, false) !=
                    newRestrictions.getBoolean(restriction, false)) {
                return true;
            }
        }
        return false;
    }

    private static void setInstallMarketAppsRestriction(ContentResolver cr, int userId,
            int settingValue) {
        android.provider.Settings.Secure.putIntForUser(
                cr, android.provider.Settings.Secure.INSTALL_NON_MARKET_APPS, settingValue, userId);
    }

    private static int getNewUserRestrictionSetting(Context context, int userId,
                String userRestriction, boolean newValue) {
        return (newValue || UserManager.get(context).hasUserRestriction(userRestriction,
                UserHandle.of(userId))) ? 0 : 1;
    }
}
