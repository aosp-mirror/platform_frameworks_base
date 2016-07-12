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

import com.google.android.collect.Sets;

import com.android.internal.util.Preconditions;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;
import android.util.Slog;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
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
            UserManager.DISALLOW_MODIFY_ACCOUNTS,
            UserManager.DISALLOW_INSTALL_APPS,
            UserManager.DISALLOW_UNINSTALL_APPS,
            UserManager.DISALLOW_SHARE_LOCATION,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_CONFIG_BLUETOOTH,
            UserManager.DISALLOW_USB_FILE_TRANSFER,
            UserManager.DISALLOW_CONFIG_CREDENTIALS,
            UserManager.DISALLOW_REMOVE_USER,
            UserManager.DISALLOW_DEBUGGING_FEATURES,
            UserManager.DISALLOW_CONFIG_VPN,
            UserManager.DISALLOW_CONFIG_TETHERING,
            UserManager.DISALLOW_NETWORK_RESET,
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_ADD_USER,
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
            UserManager.DISALLOW_SET_WALLPAPER
    });

    /**
     * Set of user restriction which we don't want to persist.
     */
    private static final Set<String> NON_PERSIST_USER_RESTRICTIONS = Sets.newArraySet(
            UserManager.DISALLOW_RECORD_AUDIO
    );

    /**
     * User restrictions that can not be set by profile owners.
     */
    private static final Set<String> DEVICE_OWNER_ONLY_RESTRICTIONS = Sets.newArraySet(
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
            UserManager.DISALLOW_DATA_ROAMING
    );

    /**
     * User restrictions that can't be changed by device owner or profile owner.
     */
    private static final Set<String> IMMUTABLE_BY_OWNERS = Sets.newArraySet(
            UserManager.DISALLOW_RECORD_AUDIO,
            UserManager.DISALLOW_WALLPAPER
    );

    /**
     * Special user restrictions that can be applied to a user as well as to all users globally,
     * depending on callers.  When device owner sets them, they'll be applied to all users.
     */
    private static final Set<String> GLOBAL_RESTRICTIONS = Sets.newArraySet(
            UserManager.DISALLOW_ADJUST_VOLUME,
            UserManager.DISALLOW_RUN_IN_BACKGROUND,
            UserManager.DISALLOW_UNMUTE_MICROPHONE
    );

    /**
     * Throws {@link IllegalArgumentException} if the given restriction name is invalid.
     */
    public static boolean isValidRestriction(@NonNull String restriction) {
        if (!USER_RESTRICTIONS.contains(restriction)) {
            Slog.e(TAG, "Unknown restriction: " + restriction);
            return false;
        }
        return true;
    }

    public static void writeRestrictions(@NonNull XmlSerializer serializer,
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
                    serializer.attribute(null, key, "true");
                }
                continue;
            }
            Log.w(TAG, "Unknown user restriction detected: " + key);
        }
        serializer.endTag(null, tag);
    }

    public static void readRestrictions(XmlPullParser parser, Bundle restrictions)
            throws IOException {
        for (String key : USER_RESTRICTIONS) {
            final String value = parser.getAttributeValue(null, key);
            if (value != null) {
                restrictions.putBoolean(key, Boolean.parseBoolean(value));
            }
        }
    }

    /**
     * @return {@code in} itself when it's not null, or an empty bundle (which can writable).
     */
    public static Bundle nonNull(@Nullable Bundle in) {
        return in != null ? in : new Bundle();
    }

    public static boolean isEmpty(@Nullable Bundle in) {
        return (in == null) || (in.size() == 0);
    }

    /**
     * Creates a copy of the {@code in} Bundle.  If {@code in} is null, it'll return an empty
     * bundle.
     *
     * <p>The resulting {@link Bundle} is always writable. (i.e. it won't return
     * {@link Bundle#EMPTY})
     */
    public static @NonNull Bundle clone(@Nullable Bundle in) {
        return (in != null) ? new Bundle(in) : new Bundle();
    }

    public static void merge(@NonNull Bundle dest, @Nullable Bundle in) {
        Preconditions.checkNotNull(dest);
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
                && !(userId != UserHandle.USER_SYSTEM
                    && DEVICE_OWNER_ONLY_RESTRICTIONS.contains(restriction));
    }

    /**
     * Takes restrictions that can be set by device owner, and sort them into what should be applied
     * globally and what should be applied only on the current user.
     */
    public static void sortToGlobalAndLocal(@Nullable Bundle in, @NonNull Bundle global,
            @NonNull Bundle local) {
        if (in == null || in.size() == 0) {
            return;
        }
        for (String key : in.keySet()) {
            if (!in.getBoolean(key)) {
                continue;
            }
            if (DEVICE_OWNER_ONLY_RESTRICTIONS.contains(key) || GLOBAL_RESTRICTIONS.contains(key)) {
                global.putBoolean(key, true);
            } else {
                local.putBoolean(key, true);
            }
        }
    }

    /**
     * @return true if two Bundles contain the same user restriction.
     * A null bundle and an empty bundle are considered to be equal.
     */
    public static boolean areEqual(@Nullable Bundle a, @Nullable Bundle b) {
        if (a == b) {
            return true;
        }
        if (isEmpty(a)) {
            return isEmpty(b);
        }
        if (isEmpty(b)) {
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
     * <p>See also {@link
     * com.android.providers.settings.SettingsProvider#isGlobalOrSecureSettingRestrictedForUser},
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
                case UserManager.DISALLOW_CONFIG_WIFI:
                    if (newValue) {
                        android.provider.Settings.Secure.putIntForUser(cr,
                                android.provider.Settings.Global
                                        .WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON, 0, userId);
                    }
                    break;
                case UserManager.DISALLOW_DATA_ROAMING:
                    if (newValue) {
                        // DISALLOW_DATA_ROAMING user restriction is set.

                        // Multi sim device.
                        SubscriptionManager subscriptionManager = new SubscriptionManager(context);
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
                        }
                    }
                    break;
                case UserManager.ENSURE_VERIFY_APPS:
                    if (newValue) {
                        android.provider.Settings.Global.putStringForUser(
                                context.getContentResolver(),
                                android.provider.Settings.Global.PACKAGE_VERIFIER_ENABLE, "1",
                                userId);
                        android.provider.Settings.Global.putStringForUser(
                                context.getContentResolver(),
                                android.provider.Settings.Global.PACKAGE_VERIFIER_INCLUDE_ADB, "1",
                                userId);
                    }
                    break;
                case UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES:
                    if (newValue) {
                        android.provider.Settings.Secure.putIntForUser(cr,
                                android.provider.Settings.Secure.INSTALL_NON_MARKET_APPS, 0,
                                userId);
                    }
                    break;
                case UserManager.DISALLOW_RUN_IN_BACKGROUND:
                    if (newValue) {
                        int currentUser = ActivityManager.getCurrentUser();
                        if (currentUser != userId && userId != UserHandle.USER_SYSTEM) {
                            try {
                                ActivityManagerNative.getDefault().stopUser(userId, false, null);
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
            }
        } finally {
            Binder.restoreCallingIdentity(id);
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
}
