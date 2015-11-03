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

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Set;

/**
 * Utility methods for uesr restrictions.
 *
 * <p>See {@link UserManagerService} for the method suffixes.
 */
public class UserRestrictionsUtils {
    private static final String TAG = "UserRestrictionsUtils";

    private UserRestrictionsUtils() {
    }

    public static final String[] USER_RESTRICTIONS = {
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
    };

    /**
     * Set of user restrictions, which can only be enforced by the system.
     */
    public static final Set<String> SYSTEM_CONTROLLED_USER_RESTRICTIONS = Sets.newArraySet(
            UserManager.DISALLOW_RECORD_AUDIO);

    /**
     * Set of user restriction which we don't want to persist.
     */
    public static final Set<String> NON_PERSIST_USER_RESTRICTIONS = Sets.newArraySet(
            UserManager.DISALLOW_RECORD_AUDIO);

    public static void writeRestrictions(XmlSerializer serializer, Bundle restrictions,
            String tag) throws IOException {
        serializer.startTag(null, tag);
        for (String key : USER_RESTRICTIONS) {
            if (restrictions.getBoolean(key)
                    && !NON_PERSIST_USER_RESTRICTIONS.contains(key)) {
                serializer.attribute(null, key, "true");
            }
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

    public static void merge(Bundle dest, Bundle in) {
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
     * Takes a new use restriction set and the previous set, and apply the restrictions that have
     * changed.
     *
     * <p>Note this method is called by {@link UserManagerService} while holding
     * {@code mRestrictionLock}. Be aware when calling into other services, which could cause
     * a deadlock.
     */
    public static void applyUserRestrictionsLR(Context context, int userId,
            Bundle newRestrictions, Bundle prevRestrictions) {
        for (String key : USER_RESTRICTIONS) {
            final boolean newValue = newRestrictions.getBoolean(key);
            final boolean prevValue = prevRestrictions.getBoolean(key);

            if (newValue != prevValue) {
                applyUserRestrictionLR(context, userId, key, newValue);
            }
        }
    }

    /**
     * Apply each user restriction.
     *
     * <p>Note this method is called by {@link UserManagerService} while holding
     * {@code mRestrictionLock}. Be aware when calling into other services, which could cause
     * a deadlock.
     */
    private static void applyUserRestrictionLR(Context context, int userId, String key,
            boolean newValue) {
        // When certain restrictions are cleared, we don't update the system settings,
        // because these settings are changeable on the Settings UI and we don't know the original
        // value -- for example LOCATION_MODE might have been off already when the restriction was
        // set, and in that case even if the restriction is lifted, changing it to ON would be
        // wrong.  So just don't do anything in such a case.  If the user hopes to enable location
        // later, they can do it on the Settings UI.

        final ContentResolver cr = context.getContentResolver();
        final long id = Binder.clearCallingIdentity();
        try {
            switch (key) {
                case UserManager.DISALLOW_CONFIG_WIFI:
                    if (newValue) {
                        android.provider.Settings.Secure.putIntForUser(cr,
                                android.provider.Settings.Secure
                                        .WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON, 0, userId);
                    }
                    break;
                case UserManager.DISALLOW_SHARE_LOCATION:
                    if (newValue) {
                        android.provider.Settings.Secure.putIntForUser(cr,
                                android.provider.Settings.Secure.LOCATION_MODE,
                                android.provider.Settings.Secure.LOCATION_MODE_OFF,
                                userId);
                        android.provider.Settings.Secure.putStringForUser(cr,
                                android.provider.Settings.Secure.LOCATION_PROVIDERS_ALLOWED, "",
                                userId);
                    }
                    // Send out notifications as some clients may want to reread the
                    // value which actually changed due to a restriction having been
                    // applied.
                    final String property =
                            android.provider.Settings.Secure.SYS_PROP_SETTING_VERSION;
                    long version = SystemProperties.getLong(property, 0) + 1;
                    SystemProperties.set(property, Long.toString(version));

                    final String name = android.provider.Settings.Secure.LOCATION_PROVIDERS_ALLOWED;
                    final Uri url = Uri.withAppendedPath(
                            android.provider.Settings.Secure.CONTENT_URI, name);
                    context.getContentResolver().notifyChange(url, null, true, userId);

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
