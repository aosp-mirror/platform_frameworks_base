/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.companion.datatransfer.permbackup.utils;

import static android.Manifest.permission_group.ACTIVITY_RECOGNITION;
import static android.Manifest.permission_group.CALENDAR;
import static android.Manifest.permission_group.CALL_LOG;
import static android.Manifest.permission_group.CAMERA;
import static android.Manifest.permission_group.CONTACTS;
import static android.Manifest.permission_group.LOCATION;
import static android.Manifest.permission_group.MICROPHONE;
import static android.Manifest.permission_group.NEARBY_DEVICES;
import static android.Manifest.permission_group.NOTIFICATIONS;
import static android.Manifest.permission_group.PHONE;
import static android.Manifest.permission_group.READ_MEDIA_AURAL;
import static android.Manifest.permission_group.READ_MEDIA_VISUAL;
import static android.Manifest.permission_group.SENSORS;
import static android.Manifest.permission_group.SMS;
import static android.Manifest.permission_group.STORAGE;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.OPSTR_LEGACY_STORAGE;
import static android.content.pm.PackageManager.FLAG_PERMISSION_RESTRICTION_INSTALLER_EXEMPT;
import static android.content.pm.PackageManager.FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT;
import static android.content.pm.PackageManager.FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT;
import static android.content.pm.PackageManager.FLAG_PERMISSION_USER_SENSITIVE_WHEN_DENIED;
import static android.content.pm.PackageManager.FLAG_PERMISSION_USER_SENSITIVE_WHEN_GRANTED;
import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.app.Application;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PermissionInfo;
import android.content.pm.ResolveInfo;
import android.hardware.SensorPrivacyManager;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.server.companion.datatransfer.permbackup.model.AppPermissionGroup;

import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Util class for BackupHelper
 */
public final class Utils {

    @Retention(SOURCE)
    @IntDef(value = {LAST_24H_SENSOR_TODAY, LAST_24H_SENSOR_YESTERDAY,
            LAST_24H_CONTENT_PROVIDER, NOT_IN_LAST_7D})
    public @interface AppPermsLastAccessType {}
    public static final int LAST_24H_SENSOR_TODAY = 1;
    public static final int LAST_24H_SENSOR_YESTERDAY = 2;
    public static final int LAST_24H_CONTENT_PROVIDER = 3;
    public static final int LAST_7D_SENSOR = 4;
    public static final int LAST_7D_CONTENT_PROVIDER = 5;
    public static final int NOT_IN_LAST_7D = 6;

    private static final List<String> SENSOR_DATA_PERMISSIONS = List.of(
            Manifest.permission_group.LOCATION,
            Manifest.permission_group.CAMERA,
            Manifest.permission_group.MICROPHONE
    );

    public static final List<String> STORAGE_SUPERGROUP_PERMISSIONS =
//            (SDK_INT < Build.VERSION_CODES.TIRAMISU) ? List.of() :
            List.of(
                    Manifest.permission_group.STORAGE,
                    Manifest.permission_group.READ_MEDIA_AURAL,
                    Manifest.permission_group.READ_MEDIA_VISUAL
            );

    private static final String LOG_TAG = "Utils";

    public static final String OS_PKG = "android";

    public static final float DEFAULT_MAX_LABEL_SIZE_PX = 500f;

    /** The time an app needs to be unused in order to be hibernated */
    public static final String PROPERTY_HIBERNATION_UNUSED_THRESHOLD_MILLIS =
            "auto_revoke_unused_threshold_millis2";

    /** The frequency of running the job for hibernating apps */
    public static final String PROPERTY_HIBERNATION_CHECK_FREQUENCY_MILLIS =
            "auto_revoke_check_frequency_millis";

    /** Whether hibernation targets apps that target a pre-S SDK */
    public static final String PROPERTY_HIBERNATION_TARGETS_PRE_S_APPS =
            "app_hibernation_targets_pre_s_apps";

    /** Whether or not app hibernation is enabled on the device **/
    public static final String PROPERTY_APP_HIBERNATION_ENABLED = "app_hibernation_enabled";

    /** Whether to show the Permissions Hub. */
    private static final String PROPERTY_PERMISSIONS_HUB_ENABLED = "permissions_hub_enabled";

    /** The timeout for one-time permissions */
    private static final String PROPERTY_ONE_TIME_PERMISSIONS_TIMEOUT_MILLIS =
            "one_time_permissions_timeout_millis";

    /** The delay before ending a one-time permission session when all processes are dead */
    private static final String PROPERTY_ONE_TIME_PERMISSIONS_KILLED_DELAY_MILLIS =
            "one_time_permissions_killed_delay_millis";

    /** Whether to show location access check notifications. */
    private static final String PROPERTY_LOCATION_ACCESS_CHECK_ENABLED =
            "location_access_check_enabled";

    /** The time an app needs to be unused in order to be hibernated */
    public static final String PROPERTY_PERMISSION_DECISIONS_CHECK_OLD_FREQUENCY_MILLIS =
            "permission_decisions_check_old_frequency_millis";

    /** The time an app needs to be unused in order to be hibernated */
    public static final String PROPERTY_PERMISSION_DECISIONS_MAX_DATA_AGE_MILLIS =
            "permission_decisions_max_data_age_millis";

    /** Whether or not warning banner is displayed when device sensors are off **/
    public static final String PROPERTY_WARNING_BANNER_DISPLAY_ENABLED = "warning_banner_enabled";

    /** All permission whitelists. */
    public static final int FLAGS_PERMISSION_WHITELIST_ALL =
            PackageManager.FLAG_PERMISSION_WHITELIST_SYSTEM
                    | PackageManager.FLAG_PERMISSION_WHITELIST_UPGRADE
                    | PackageManager.FLAG_PERMISSION_WHITELIST_INSTALLER;

    /** All permission restriction exemptions. */
    public static final int FLAGS_PERMISSION_RESTRICTION_ANY_EXEMPT =
            FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT
                    | FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT
                    | FLAG_PERMISSION_RESTRICTION_INSTALLER_EXEMPT;

    /**
     * The default length of the timeout for one-time permissions
     */
    public static final long ONE_TIME_PERMISSIONS_TIMEOUT_MILLIS = 1 * 60 * 1000; // 1 minute

    /**
     * The default length to wait before ending a one-time permission session after all processes
     * are dead.
     */
    public static final long ONE_TIME_PERMISSIONS_KILLED_DELAY_MILLIS = 5 * 1000;

    /** Mapping permission -> group for all dangerous platform permissions */
    private static final ArrayMap<String, String> PLATFORM_PERMISSIONS;

    /** Mapping group -> permissions for all dangerous platform permissions */
    private static final ArrayMap<String, ArrayList<String>> PLATFORM_PERMISSION_GROUPS;

    /** Set of groups that will be able to receive one-time grant */
    private static final ArraySet<String> ONE_TIME_PERMISSION_GROUPS;

    /** Permission -> Sensor codes */
    private static final ArrayMap<String, Integer> PERM_SENSOR_CODES;

    public static final int FLAGS_ALWAYS_USER_SENSITIVE =
            FLAG_PERMISSION_USER_SENSITIVE_WHEN_GRANTED
                    | FLAG_PERMISSION_USER_SENSITIVE_WHEN_DENIED;

    private static final String SYSTEM_PKG = "android";

    private static final String SYSTEM_AMBIENT_AUDIO_INTELLIGENCE =
            "android.app.role.SYSTEM_AMBIENT_AUDIO_INTELLIGENCE";
    private static final String SYSTEM_UI_INTELLIGENCE =
            "android.app.role.SYSTEM_UI_INTELLIGENCE";
    private static final String SYSTEM_AUDIO_INTELLIGENCE =
            "android.app.role.SYSTEM_AUDIO_INTELLIGENCE";
    private static final String SYSTEM_NOTIFICATION_INTELLIGENCE =
            "android.app.role.SYSTEM_NOTIFICATION_INTELLIGENCE";
    private static final String SYSTEM_TEXT_INTELLIGENCE =
            "android.app.role.SYSTEM_TEXT_INTELLIGENCE";
    private static final String SYSTEM_VISUAL_INTELLIGENCE =
            "android.app.role.SYSTEM_VISUAL_INTELLIGENCE";

    // TODO: theianchen Using hardcoded values here as a WIP solution for now.
    private static final String[] EXEMPTED_ROLES = {
            SYSTEM_AMBIENT_AUDIO_INTELLIGENCE,
            SYSTEM_UI_INTELLIGENCE,
            SYSTEM_AUDIO_INTELLIGENCE,
            SYSTEM_NOTIFICATION_INTELLIGENCE,
            SYSTEM_TEXT_INTELLIGENCE,
            SYSTEM_VISUAL_INTELLIGENCE,
    };

    static {
        PLATFORM_PERMISSIONS = new ArrayMap<>();

        PLATFORM_PERMISSIONS.put(Manifest.permission.READ_CONTACTS, CONTACTS);
        PLATFORM_PERMISSIONS.put(Manifest.permission.WRITE_CONTACTS, CONTACTS);
        PLATFORM_PERMISSIONS.put(Manifest.permission.GET_ACCOUNTS, CONTACTS);

        PLATFORM_PERMISSIONS.put(Manifest.permission.READ_CALENDAR, CALENDAR);
        PLATFORM_PERMISSIONS.put(Manifest.permission.WRITE_CALENDAR, CALENDAR);

        PLATFORM_PERMISSIONS.put(Manifest.permission.SEND_SMS, SMS);
        PLATFORM_PERMISSIONS.put(Manifest.permission.RECEIVE_SMS, SMS);
        PLATFORM_PERMISSIONS.put(Manifest.permission.READ_SMS, SMS);
        PLATFORM_PERMISSIONS.put(Manifest.permission.RECEIVE_MMS, SMS);
        PLATFORM_PERMISSIONS.put(Manifest.permission.RECEIVE_WAP_PUSH, SMS);
        PLATFORM_PERMISSIONS.put(Manifest.permission.READ_CELL_BROADCASTS, SMS);

        // If permissions are added to the Storage group, they must be added to the
        // STORAGE_PERMISSIONS list in PermissionManagerService in frameworks/base
        PLATFORM_PERMISSIONS.put(Manifest.permission.READ_EXTERNAL_STORAGE, STORAGE);
        PLATFORM_PERMISSIONS.put(Manifest.permission.WRITE_EXTERNAL_STORAGE, STORAGE);
//        if (SDK_INT < Build.VERSION_CODES.TIRAMISU) {
//            PLATFORM_PERMISSIONS.put(Manifest.permission.ACCESS_MEDIA_LOCATION, STORAGE);
//        }

//        if (SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        PLATFORM_PERMISSIONS.put(Manifest.permission.READ_MEDIA_AUDIO, READ_MEDIA_AURAL);
        PLATFORM_PERMISSIONS.put(Manifest.permission.READ_MEDIA_IMAGES, READ_MEDIA_VISUAL);
        PLATFORM_PERMISSIONS.put(Manifest.permission.READ_MEDIA_VIDEO, READ_MEDIA_VISUAL);
        PLATFORM_PERMISSIONS.put(Manifest.permission.ACCESS_MEDIA_LOCATION, READ_MEDIA_VISUAL);
//        }

        PLATFORM_PERMISSIONS.put(Manifest.permission.ACCESS_FINE_LOCATION, LOCATION);
        PLATFORM_PERMISSIONS.put(Manifest.permission.ACCESS_COARSE_LOCATION, LOCATION);
        PLATFORM_PERMISSIONS.put(Manifest.permission.ACCESS_BACKGROUND_LOCATION, LOCATION);

//        if (SDK_INT >= Build.VERSION_CODES.S) {
        PLATFORM_PERMISSIONS.put(Manifest.permission.BLUETOOTH_ADVERTISE, NEARBY_DEVICES);
        PLATFORM_PERMISSIONS.put(Manifest.permission.BLUETOOTH_CONNECT, NEARBY_DEVICES);
        PLATFORM_PERMISSIONS.put(Manifest.permission.BLUETOOTH_SCAN, NEARBY_DEVICES);
        PLATFORM_PERMISSIONS.put(Manifest.permission.UWB_RANGING, NEARBY_DEVICES);
//        }
//        if (SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        PLATFORM_PERMISSIONS.put(Manifest.permission.NEARBY_WIFI_DEVICES, NEARBY_DEVICES);
//        }

        PLATFORM_PERMISSIONS.put(Manifest.permission.READ_CALL_LOG, CALL_LOG);
        PLATFORM_PERMISSIONS.put(Manifest.permission.WRITE_CALL_LOG, CALL_LOG);
        PLATFORM_PERMISSIONS.put(Manifest.permission.PROCESS_OUTGOING_CALLS, CALL_LOG);

        PLATFORM_PERMISSIONS.put(Manifest.permission.READ_PHONE_STATE, PHONE);
        PLATFORM_PERMISSIONS.put(Manifest.permission.READ_PHONE_NUMBERS, PHONE);
        PLATFORM_PERMISSIONS.put(Manifest.permission.CALL_PHONE, PHONE);
        PLATFORM_PERMISSIONS.put(Manifest.permission.ADD_VOICEMAIL, PHONE);
        PLATFORM_PERMISSIONS.put(Manifest.permission.USE_SIP, PHONE);
        PLATFORM_PERMISSIONS.put(Manifest.permission.ANSWER_PHONE_CALLS, PHONE);
        PLATFORM_PERMISSIONS.put(Manifest.permission.ACCEPT_HANDOVER, PHONE);

        PLATFORM_PERMISSIONS.put(Manifest.permission.RECORD_AUDIO, MICROPHONE);
//        if (SDK_INT >= Build.VERSION_CODES.S) {
        PLATFORM_PERMISSIONS.put(Manifest.permission.RECORD_BACKGROUND_AUDIO, MICROPHONE);
//        }

        PLATFORM_PERMISSIONS.put(Manifest.permission.ACTIVITY_RECOGNITION, ACTIVITY_RECOGNITION);

        PLATFORM_PERMISSIONS.put(Manifest.permission.CAMERA, CAMERA);
//        if (SDK_INT >= Build.VERSION_CODES.S) {
        PLATFORM_PERMISSIONS.put(Manifest.permission.BACKGROUND_CAMERA, CAMERA);
//        }

        PLATFORM_PERMISSIONS.put(Manifest.permission.BODY_SENSORS, SENSORS);

//        if (SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        PLATFORM_PERMISSIONS.put(Manifest.permission.POST_NOTIFICATIONS, NOTIFICATIONS);
        PLATFORM_PERMISSIONS.put(Manifest.permission.BODY_SENSORS_BACKGROUND, SENSORS);
//        }

        PLATFORM_PERMISSION_GROUPS = new ArrayMap<>();
        int numPlatformPermissions = PLATFORM_PERMISSIONS.size();
        for (int i = 0; i < numPlatformPermissions; i++) {
            String permission = PLATFORM_PERMISSIONS.keyAt(i);
            String permissionGroup = PLATFORM_PERMISSIONS.valueAt(i);

            ArrayList<String> permissionsOfThisGroup = PLATFORM_PERMISSION_GROUPS.get(
                    permissionGroup);
            if (permissionsOfThisGroup == null) {
                permissionsOfThisGroup = new ArrayList<>();
                PLATFORM_PERMISSION_GROUPS.put(permissionGroup, permissionsOfThisGroup);
            }

            permissionsOfThisGroup.add(permission);
        }

        ONE_TIME_PERMISSION_GROUPS = new ArraySet<>();
        ONE_TIME_PERMISSION_GROUPS.add(LOCATION);
        ONE_TIME_PERMISSION_GROUPS.add(CAMERA);
        ONE_TIME_PERMISSION_GROUPS.add(MICROPHONE);

        PERM_SENSOR_CODES = new ArrayMap<>();
//        if (SDK_INT >= Build.VERSION_CODES.S) {
        PERM_SENSOR_CODES.put(CAMERA, SensorPrivacyManager.Sensors.CAMERA);
        PERM_SENSOR_CODES.put(MICROPHONE, SensorPrivacyManager.Sensors.MICROPHONE);
//        }

    }

    private Utils() {
        /* do nothing - hide constructor */
    }

    private static ArrayMap<UserHandle, Context> sUserContexts = new ArrayMap<>();

    /**
     * Creates and caches a PackageContext for the requested user, or returns the previously cached
     * value. The package of the PackageContext is the application's package.
     *
     * @param app The currently running application
     * @param user The desired user for the context
     *
     * @return The generated or cached Context for the requested user
     *
     * @throws PackageManager.NameNotFoundException If the app has no package name attached
     */
    public static @NonNull Context getUserContext(Application app, UserHandle user) throws
            PackageManager.NameNotFoundException {
        if (!sUserContexts.containsKey(user)) {
            sUserContexts.put(user, app.getApplicationContext()
                    .createPackageContextAsUser(app.getPackageName(), 0, user));
        }
        return sUserContexts.get(user);
    }

    /**
     * Returns true if a permission is dangerous, installed, and not removed
     * @param permissionInfo The permission we wish to check
     * @return If all of the conditions are met
     */
    public static boolean isPermissionDangerousInstalledNotRemoved(PermissionInfo permissionInfo) {
        return permissionInfo != null
                && permissionInfo.getProtection() == PermissionInfo.PROTECTION_DANGEROUS
                && (permissionInfo.flags & PermissionInfo.FLAG_INSTALLED) != 0
                && (permissionInfo.flags & PermissionInfo.FLAG_REMOVED) == 0;
    }

    /**
     * Get permission group a platform permission belongs to, or null if the permission is not a
     * platform permission.
     *
     * @param permission the permission to resolve
     *
     * @return The group the permission belongs to
     */
    public static @Nullable String getGroupOfPlatformPermission(@NonNull String permission) {
        return PLATFORM_PERMISSIONS.get(permission);
    }

    /**
     * Get name of the permission group a permission belongs to.
     *
     * @param permission the {@link PermissionInfo info} of the permission to resolve
     *
     * @return The group the permission belongs to
     */
    public static @Nullable String getGroupOfPermission(@NonNull PermissionInfo permission) {
        String groupName = Utils.getGroupOfPlatformPermission(permission.name);
        if (groupName == null) {
            groupName = permission.group;
        }

        return groupName;
    }

    /**
     * Get the names for all platform permissions belonging to a group.
     *
     * @param group the group
     *
     * @return The permission names  or an empty list if the
     *         group is not does not have platform runtime permissions
     */
    public static @NonNull List<String> getPlatformPermissionNamesOfGroup(@NonNull String group) {
        final ArrayList<String> permissions = PLATFORM_PERMISSION_GROUPS.get(group);
        return (permissions != null) ? permissions : Collections.emptyList();
    }

    /**
     * Get the {@link PermissionInfo infos} for all platform permissions belonging to a group.
     *
     * @param pm    Package manager to use to resolve permission infos
     * @param group the group
     *
     * @return The infos for platform permissions belonging to the group or an empty list if the
     *         group is not does not have platform runtime permissions
     */
    public static @NonNull List<PermissionInfo> getPlatformPermissionsOfGroup(
            @NonNull PackageManager pm, @NonNull String group) {
        ArrayList<PermissionInfo> permInfos = new ArrayList<>();

        ArrayList<String> permissions = PLATFORM_PERMISSION_GROUPS.get(group);
        if (permissions == null) {
            return Collections.emptyList();
        }

        int numPermissions = permissions.size();
        for (int i = 0; i < numPermissions; i++) {
            String permName = permissions.get(i);
            PermissionInfo permInfo;
            try {
                permInfo = pm.getPermissionInfo(permName, 0);
            } catch (PackageManager.NameNotFoundException e) {
                throw new IllegalStateException(permName + " not defined by platform", e);
            }

            permInfos.add(permInfo);
        }

        return permInfos;
    }

    /**
     * Get the {@link PermissionInfo infos} for all permission infos belonging to a group.
     *
     * @param pm    Package manager to use to resolve permission infos
     * @param group the group
     *
     * @return The infos of permissions belonging to the group or an empty list if the group
     *         does not have runtime permissions
     */
    public static @NonNull List<PermissionInfo> getPermissionInfosForGroup(
            @NonNull PackageManager pm, @NonNull String group)
            throws PackageManager.NameNotFoundException {
        List<PermissionInfo> permissions = pm.queryPermissionsByGroup(group, 0);
        permissions.addAll(getPlatformPermissionsOfGroup(pm, group));

        /*
         * If the undefined group is requested, the package manager will return all platform
         * permissions, since they are marked as Undefined in the manifest. Do not return these
         * permissions.
         */
        if (group.equals(Manifest.permission_group.UNDEFINED)) {
            List<PermissionInfo> undefinedPerms = new ArrayList<>();
            for (PermissionInfo permissionInfo : permissions) {
                String permGroup = getGroupOfPlatformPermission(permissionInfo.name);
                if (permGroup == null || permGroup.equals(Manifest.permission_group.UNDEFINED)) {
                    undefinedPerms.add(permissionInfo);
                }
            }
            return undefinedPerms;
        }

        return permissions;
    }

    /**
     * Get the {@link PermissionInfo infos} for all runtime installed permission infos belonging to
     * a group.
     *
     * @param pm    Package manager to use to resolve permission infos
     * @param group the group
     *
     * @return The infos of installed runtime permissions belonging to the group or an empty list
     * if the group does not have runtime permissions
     */
    public static @NonNull List<PermissionInfo> getInstalledRuntimePermissionInfosForGroup(
            @NonNull PackageManager pm, @NonNull String group)
            throws PackageManager.NameNotFoundException {
        List<PermissionInfo> permissions = pm.queryPermissionsByGroup(group, 0);
        permissions.addAll(getPlatformPermissionsOfGroup(pm, group));

        List<PermissionInfo> installedRuntime = new ArrayList<>();
        for (PermissionInfo permissionInfo: permissions) {
            if (permissionInfo.getProtection() == PermissionInfo.PROTECTION_DANGEROUS
                    && (permissionInfo.flags & PermissionInfo.FLAG_INSTALLED) != 0
                    && (permissionInfo.flags & PermissionInfo.FLAG_REMOVED) == 0) {
                installedRuntime.add(permissionInfo);
            }
        }

        /*
         * If the undefined group is requested, the package manager will return all platform
         * permissions, since they are marked as Undefined in the manifest. Do not return these
         * permissions.
         */
        if (group.equals(Manifest.permission_group.UNDEFINED)) {
            List<PermissionInfo> undefinedPerms = new ArrayList<>();
            for (PermissionInfo permissionInfo : installedRuntime) {
                String permGroup = getGroupOfPlatformPermission(permissionInfo.name);
                if (permGroup == null || permGroup.equals(Manifest.permission_group.UNDEFINED)) {
                    undefinedPerms.add(permissionInfo);
                }
            }
            return undefinedPerms;
        }

        return installedRuntime;
    }

    /**
     * Get the {@link PackageItemInfo infos} for the given permission group.
     *
     * @param groupName the group
     * @param context the {@code Context} to retrieve {@code PackageManager}
     *
     * @return The info of permission group or null if the group does not have runtime permissions.
     */
    public static @Nullable PackageItemInfo getGroupInfo(@NonNull String groupName,
            @NonNull Context context) {
        try {
            return context.getPackageManager().getPermissionGroupInfo(groupName, 0);
        } catch (NameNotFoundException e) {
            /* ignore */
        }
        try {
            return context.getPackageManager().getPermissionInfo(groupName, 0);
        } catch (NameNotFoundException e) {
            /* ignore */
        }
        return null;
    }

    /**
     * Get the {@link PermissionInfo infos} for all permission infos belonging to a group.
     *
     * @param groupName the group
     * @param context the {@code Context} to retrieve {@code PackageManager}
     *
     * @return The infos of permissions belonging to the group or null if the group does not have
     *         runtime permissions.
     */
    public static @Nullable List<PermissionInfo> getGroupPermissionInfos(@NonNull String groupName,
            @NonNull Context context) {
        try {
            return Utils.getPermissionInfosForGroup(context.getPackageManager(), groupName);
        } catch (NameNotFoundException e) {
            /* ignore */
        }
        try {
            PermissionInfo permissionInfo = context.getPackageManager()
                    .getPermissionInfo(groupName, 0);
            List<PermissionInfo> permissions = new ArrayList<>();
            permissions.add(permissionInfo);
            return permissions;
        } catch (NameNotFoundException e) {
            /* ignore */
        }
        return null;
    }

    /**
     * Get the names of the platform permission groups.
     *
     * @return the names of the platform permission groups.
     */
    public static List<String> getPlatformPermissionGroups() {
        return new ArrayList<>(PLATFORM_PERMISSION_GROUPS.keySet());
    }

    /**
     * Get the names of the runtime platform permissions
     *
     * @return the names of the runtime platform permissions.
     */
    public static List<String> getRuntimePlatformPermissionNames() {
        return new ArrayList<>(PLATFORM_PERMISSIONS.keySet());
    }

    /**
     * Is the permissions a platform runtime permission
     *
     * @return the names of the runtime platform permissions.
     */
    public static boolean isRuntimePlatformPermission(@NonNull String permission) {
        return PLATFORM_PERMISSIONS.containsKey(permission);
    }

    /**
     * Is the group or background group user sensitive?
     *
     * @param group The group that might be user sensitive
     *
     * @return {@code true} if the group (or it's subgroup) is user sensitive.
     */
    public static boolean isGroupOrBgGroupUserSensitive(AppPermissionGroup group) {
        return group.isUserSensitive() || (group.getBackgroundPermissions() != null
                && group.getBackgroundPermissions().isUserSensitive());
    }

    /**
     * Whether or not the given package has non-isolated storage permissions
     * @param context The current context
     * @param packageName The package name to check
     * @return True if the package has access to non-isolated storage, false otherwise
     * @throws NameNotFoundException
     */
    public static boolean isNonIsolatedStorage(@NonNull Context context,
            @NonNull String packageName) throws NameNotFoundException {
        PackageInfo packageInfo = context.getPackageManager().getPackageInfo(packageName, 0);
        AppOpsManager manager = context.getSystemService(AppOpsManager.class);


        return packageInfo.applicationInfo.targetSdkVersion < Build.VERSION_CODES.P
                || (packageInfo.applicationInfo.targetSdkVersion < Build.VERSION_CODES.R
                && manager.unsafeCheckOpNoThrow(OPSTR_LEGACY_STORAGE,
                packageInfo.applicationInfo.uid, packageInfo.packageName) == MODE_ALLOWED);
    }

    /**
     * Build a string representing the given time if it happened on the current day and the date
     * otherwise.
     *
     * @param context the context.
     * @param lastAccessTime the time in milliseconds.
     *
     * @return a string representing the time or date of the given time or null if the time is 0.
     */
    public static @Nullable String getAbsoluteTimeString(@NonNull Context context,
            long lastAccessTime) {
        if (lastAccessTime == 0) {
            return null;
        }
        if (isToday(lastAccessTime)) {
            return DateFormat.getTimeFormat(context).format(lastAccessTime);
        } else {
            return DateFormat.getMediumDateFormat(context).format(lastAccessTime);
        }
    }

    /**
     * Check whether the given time (in milliseconds) is in the current day.
     *
     * @param time the time in milliseconds
     *
     * @return whether the given time is in the current day.
     */
    private static boolean isToday(long time) {
        Calendar today = Calendar.getInstance(Locale.getDefault());
        today.setTimeInMillis(System.currentTimeMillis());
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);

        Calendar date = Calendar.getInstance(Locale.getDefault());
        date.setTimeInMillis(time);
        return !date.before(today);
    }

    /**
     * Whether the Location Access Check is enabled.
     *
     * @return {@code true} iff the Location Access Check is enabled.
     */
    public static boolean isLocationAccessCheckEnabled() {
        return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_PRIVACY,
                PROPERTY_LOCATION_ACCESS_CHECK_ENABLED, true);
    }

    /**
     * Get one time permissions timeout
     */
    public static long getOneTimePermissionsTimeout() {
        return DeviceConfig.getLong(DeviceConfig.NAMESPACE_PERMISSIONS,
                PROPERTY_ONE_TIME_PERMISSIONS_TIMEOUT_MILLIS, ONE_TIME_PERMISSIONS_TIMEOUT_MILLIS);
    }

    /**
     * Returns the delay in milliseconds before revoking permissions at the end of a one-time
     * permission session if all processes have been killed.
     * If the session was triggered by a self-revocation, then revocation should happen
     * immediately. For a regular one-time permission session, a grace period allows a quick
     * app restart without losing the permission.
     * @param isSelfRevoked If true, return the delay for a self-revocation session. Otherwise,
     *                      return delay for a regular one-time permission session.
     */
    public static long getOneTimePermissionsKilledDelay(boolean isSelfRevoked) {
        if (isSelfRevoked) {
            // For a self-revoked session, we revoke immediately when the process dies.
            return 0;
        }
        return DeviceConfig.getLong(DeviceConfig.NAMESPACE_PERMISSIONS,
                PROPERTY_ONE_TIME_PERMISSIONS_KILLED_DELAY_MILLIS,
                ONE_TIME_PERMISSIONS_KILLED_DELAY_MILLIS);
    }

    /**
     * Whether the permission group supports one-time
     * @param permissionGroup The permission group to check
     * @return {@code true} iff the group supports one-time
     */
    public static boolean supportsOneTimeGrant(String permissionGroup) {
        return ONE_TIME_PERMISSION_GROUPS.contains(permissionGroup);
    }

    /**
     * Checks whether a package has an active one-time permission according to the system server's
     * flags
     *
     * @param context the {@code Context} to retrieve {@code PackageManager}
     * @param packageName The package to check for
     * @return Whether a package has an active one-time permission
     */
    public static boolean hasOneTimePermissions(Context context, String packageName) {
        String[] permissions;
        PackageManager pm = context.getPackageManager();
        try {
            permissions = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
                    .requestedPermissions;
        } catch (NameNotFoundException e) {
            Log.w(LOG_TAG, "Checking for one-time permissions in nonexistent package");
            return false;
        }
        if (permissions == null) {
            return false;
        }
        for (String permissionName : permissions) {
            if ((pm.getPermissionFlags(permissionName, packageName, Process.myUserHandle())
                    & PackageManager.FLAG_PERMISSION_ONE_TIME) != 0
                    && pm.checkPermission(permissionName, packageName)
                    == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets the label of the Settings application
     *
     * @param pm The packageManager used to get the activity resolution
     *
     * @return The CharSequence title of the settings app
     */
    @Nullable
    public static CharSequence getSettingsLabelForNotifications(PackageManager pm) {
        // We pretend we're the Settings app sending the notification, so figure out its name.
        Intent openSettingsIntent = new Intent(Settings.ACTION_SETTINGS);
        ResolveInfo resolveInfo = pm.resolveActivity(openSettingsIntent, MATCH_SYSTEM_ONLY);
        if (resolveInfo == null) {
            return null;
        }
        return pm.getApplicationLabel(resolveInfo.activityInfo.applicationInfo);
    }

    /**
     * Get all the exempted packages.
     */
    public static Set<String> getExemptedPackages(@NonNull RoleManager roleManager) {
        Set<String> exemptedPackages = new HashSet<>();

        exemptedPackages.add(SYSTEM_PKG);
        for (int i = 0; i < EXEMPTED_ROLES.length; i++) {
            exemptedPackages.addAll(roleManager.getRoleHolders(EXEMPTED_ROLES[i]));
        }

        return exemptedPackages;
    }

    /**
     * Returns if the permission group is Camera or Microphone (status bar indicators).
     **/
    public static boolean isStatusBarIndicatorPermission(@NonNull String permissionGroupName) {
        return CAMERA.equals(permissionGroupName) || MICROPHONE.equals(permissionGroupName);
    }

    /**
     * Navigate to notification settings for all apps
     * @param context The current Context
     */
    public static void navigateToNotificationSettings(@NonNull Context context) {
        Intent notificationIntent = new Intent(Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS);
        context.startActivity(notificationIntent);
    }

    /**
     * Navigate to notification settings for an app
     * @param context The current Context
     * @param packageName The package to navigate to
     * @param user Specifies the user of the package which should be navigated to. If null, the
     *             current user is used.
     */
    public static void navigateToAppNotificationSettings(@NonNull Context context,
            @NonNull String packageName, @NonNull UserHandle user) {
        Intent notificationIntent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        notificationIntent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName);
        context.startActivityAsUser(notificationIntent, user);
    }

    /**
     * Returns if a card should be shown if the sensor is blocked
     **/
    public static boolean shouldDisplayCardIfBlocked(@NonNull String permissionGroupName) {
        return DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_PRIVACY, PROPERTY_WARNING_BANNER_DISPLAY_ENABLED, true) && (
                CAMERA.equals(permissionGroupName) || MICROPHONE.equals(permissionGroupName)
                        || LOCATION.equals(permissionGroupName));
    }
}
