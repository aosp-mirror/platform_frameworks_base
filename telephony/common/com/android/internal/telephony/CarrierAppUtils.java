/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.telephony;

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.CarrierAssociatedAppEntry;
import android.os.SystemConfigManager;
import android.os.UserHandle;
import android.permission.LegacyPermissionManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.util.TelephonyUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Utilities to control the states of the system bundled (preinstalled) carrier applications.
 * @hide
 */
public final class CarrierAppUtils {
    private static final String TAG = "CarrierAppUtils";

    private static final boolean DEBUG = false; // STOPSHIP if true

    private CarrierAppUtils() {}

    /**
     * Handle preinstalled carrier apps which should be disabled until a matching SIM is inserted.
     *
     * Evaluates the list of applications in
     * {@link SystemConfigManager#getDisabledUntilUsedPreinstalledCarrierApps()}. We want to disable
     * each such application which is present on the system image until the user inserts a SIM
     * which causes that application to gain carrier privilege (indicating a "match"), without
     * interfering with the user if they opt to enable/disable the app explicitly.
     *
     * So, for each such app, we either disable until used IFF the app is not carrier privileged AND
     * in the default state (e.g. not explicitly DISABLED/DISABLED_BY_USER/ENABLED), or we enable if
     * the app is carrier privileged and in either the default state or DISABLED_UNTIL_USED.
     *
     * In addition, there is a list of carrier-associated applications in
     * {@link SystemConfigManager#getDisabledUntilUsedPreinstalledCarrierAssociatedApps}. Each app
     * in this list is associated with a carrier app. When the given carrier app is enabled/disabled
     * per the above, the associated applications are enabled/disabled to match.
     *
     * When enabling a carrier app we also grant it default permissions.
     *
     * This method is idempotent and is safe to be called at any time; it should be called once at
     * system startup prior to any application running, as well as any time the set of carrier
     * privileged apps may have changed.
     */
    public static synchronized void disableCarrierAppsUntilPrivileged(String callingPackage,
            TelephonyManager telephonyManager, @UserIdInt int userId, Context context) {
        if (DEBUG) {
            Log.d(TAG, "disableCarrierAppsUntilPrivileged");
        }
        SystemConfigManager config = context.getSystemService(SystemConfigManager.class);
        Set<String> systemCarrierAppsDisabledUntilUsed =
                config.getDisabledUntilUsedPreinstalledCarrierApps();
        Map<String, List<CarrierAssociatedAppEntry>> systemCarrierAssociatedAppsDisabledUntilUsed =
                config.getDisabledUntilUsedPreinstalledCarrierAssociatedAppEntries();
        ContentResolver contentResolver = getContentResolverForUser(context, userId);
        disableCarrierAppsUntilPrivileged(callingPackage, telephonyManager, contentResolver,
                userId, systemCarrierAppsDisabledUntilUsed,
                systemCarrierAssociatedAppsDisabledUntilUsed, context);
    }

    /**
     * Like {@link #disableCarrierAppsUntilPrivileged(String, TelephonyManager, int, Context)},
     * but assumes that no carrier apps have carrier privileges.
     *
     * This prevents a potential race condition on first boot - since the app's default state is
     * enabled, we will initially disable it when the telephony stack is first initialized as it has
     * not yet read the carrier privilege rules. However, since telephony is initialized later on
     * late in boot, the app being disabled may have already been started in response to certain
     * broadcasts. The app will continue to run (briefly) after being disabled, before the Package
     * Manager can kill it, and this can lead to crashes as the app is in an unexpected state.
     */
    public static synchronized void disableCarrierAppsUntilPrivileged(String callingPackage,
            @UserIdInt int userId, Context context) {
        if (DEBUG) {
            Log.d(TAG, "disableCarrierAppsUntilPrivileged");
        }
        SystemConfigManager config = context.getSystemService(SystemConfigManager.class);
        Set<String> systemCarrierAppsDisabledUntilUsed =
                config.getDisabledUntilUsedPreinstalledCarrierApps();

        Map<String, List<CarrierAssociatedAppEntry>> systemCarrierAssociatedAppsDisabledUntilUsed =
                config.getDisabledUntilUsedPreinstalledCarrierAssociatedAppEntries();
        ContentResolver contentResolver = getContentResolverForUser(context, userId);
        disableCarrierAppsUntilPrivileged(callingPackage, null /* telephonyManager */,
                contentResolver, userId, systemCarrierAppsDisabledUntilUsed,
                systemCarrierAssociatedAppsDisabledUntilUsed, context);
    }

    private static ContentResolver getContentResolverForUser(Context context,
            @UserIdInt int userId) {
        Context userContext = context.createContextAsUser(UserHandle.of(userId), 0);
        return userContext.getContentResolver();
    }

    private static boolean isUpdatedSystemApp(ApplicationInfo ai) {
        return (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
    }

    /**
     * Disable carrier apps until they are privileged
     * Must be public b/c framework unit tests can't access package-private methods.
     */
    // Must be public b/c framework unit tests can't access package-private methods.
    @VisibleForTesting
    public static void disableCarrierAppsUntilPrivileged(String callingPackage,
            @Nullable TelephonyManager telephonyManager, ContentResolver contentResolver,
            int userId, Set<String> systemCarrierAppsDisabledUntilUsed,
            Map<String, List<CarrierAssociatedAppEntry>>
            systemCarrierAssociatedAppsDisabledUntilUsed, Context context) {
        PackageManager packageManager = context.getPackageManager();
        LegacyPermissionManager permissionManager = (LegacyPermissionManager)
                context.getSystemService(Context.LEGACY_PERMISSION_SERVICE);
        List<ApplicationInfo> candidates = getDefaultCarrierAppCandidatesHelper(
                userId, systemCarrierAppsDisabledUntilUsed, context);
        if (candidates == null || candidates.isEmpty()) {
            return;
        }

        Map<String, List<AssociatedAppInfo>> associatedApps = getDefaultCarrierAssociatedAppsHelper(
                userId, systemCarrierAssociatedAppsDisabledUntilUsed, context);

        List<String> enabledCarrierPackages = new ArrayList<>();
        int carrierAppsHandledSdk =
                Settings.Secure.getIntForUser(contentResolver, Settings.Secure.CARRIER_APPS_HANDLED,
                        0, contentResolver.getUserId());
        if (DEBUG) {
            Log.i(TAG, "Last execution SDK: " + carrierAppsHandledSdk);
        }
        boolean hasRunEver = carrierAppsHandledSdk != 0; // SDKs < R used to just set 1 here
        boolean hasRunForSdk = carrierAppsHandledSdk == Build.VERSION.SDK_INT;

        try {
            for (ApplicationInfo ai : candidates) {
                String packageName = ai.packageName;
                boolean hasPrivileges = telephonyManager != null
                        && telephonyManager.checkCarrierPrivilegesForPackageAnyPhone(packageName)
                                == TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS;

                // add hiddenUntilInstalled flag for carrier apps and associated apps
                packageManager.setSystemAppState(
                        packageName, PackageManager.SYSTEM_APP_STATE_HIDDEN_UNTIL_INSTALLED_HIDDEN);
                List<AssociatedAppInfo> associatedAppList = associatedApps.get(packageName);
                if (associatedAppList != null) {
                    for (AssociatedAppInfo associatedApp : associatedAppList) {
                        packageManager.setSystemAppState(associatedApp.appInfo.packageName,
                                PackageManager.SYSTEM_APP_STATE_HIDDEN_UNTIL_INSTALLED_HIDDEN);
                    }
                }

                int enabledSetting = context.createContextAsUser(UserHandle.of(userId), 0)
                        .getPackageManager().getApplicationEnabledSetting(packageName);
                if (hasPrivileges) {
                    // Only update enabled state for the app on /system. Once it has been
                    // updated we shouldn't touch it.
                    if (!isUpdatedSystemApp(ai) && enabledSetting
                            == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                            || enabledSetting
                            == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
                            || (ai.flags & ApplicationInfo.FLAG_INSTALLED) == 0) {
                        Log.i(TAG, "Update state (" + packageName + "): ENABLED for user "
                                + userId);
                        context.createContextAsUser(UserHandle.of(userId), 0)
                                .getPackageManager()
                                .setSystemAppState(
                                        packageName, PackageManager.SYSTEM_APP_STATE_INSTALLED);
                        context.createPackageContextAsUser(callingPackage, 0, UserHandle.of(userId))
                                .getPackageManager()
                                .setApplicationEnabledSetting(
                                        packageName,
                                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                                        PackageManager.DONT_KILL_APP);
                    }

                    // Also enable any associated apps for this carrier app.
                    if (associatedAppList != null) {
                        for (AssociatedAppInfo associatedApp : associatedAppList) {
                            int associatedAppEnabledSetting = context
                                    .createContextAsUser(UserHandle.of(userId), 0)
                                    .getPackageManager()
                                    .getApplicationEnabledSetting(
                                            associatedApp.appInfo.packageName);
                            boolean associatedAppInstalled = (associatedApp.appInfo.flags
                                    & ApplicationInfo.FLAG_INSTALLED) != 0;
                            if (DEBUG) {
                                Log.i(TAG, "(hasPrivileges) associated app "
                                        + associatedApp.appInfo.packageName + ", enabled = "
                                        + associatedAppEnabledSetting + ", installed = "
                                        + associatedAppInstalled);
                            }
                            if (associatedAppEnabledSetting
                                    == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                                    || associatedAppEnabledSetting
                                    == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
                                    || !associatedAppInstalled) {
                                Log.i(TAG, "Update associated state ("
                                        + associatedApp.appInfo.packageName + "): ENABLED for user "
                                        + userId);
                                context.createContextAsUser(UserHandle.of(userId), 0)
                                        .getPackageManager()
                                        .setSystemAppState(associatedApp.appInfo.packageName,
                                                PackageManager.SYSTEM_APP_STATE_INSTALLED);
                                context.createPackageContextAsUser(
                                        callingPackage, 0, UserHandle.of(userId))
                                        .getPackageManager()
                                        .setApplicationEnabledSetting(
                                                associatedApp.appInfo.packageName,
                                                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                                                PackageManager.DONT_KILL_APP);
                            }
                        }
                    }

                    // Always re-grant default permissions to carrier apps w/ privileges.
                    enabledCarrierPackages.add(ai.packageName);
                } else {  // No carrier privileges
                    // Only uninstall system carrier apps that fulfill ALL conditions below:
                    // 1. It has no carrier privileges
                    // 2. It has never been uninstalled before (i.e. we uninstall at most once)
                    // 3. It has not been installed as an update from its system built-in version
                    // 4. It is in default state (not explicitly DISABLED/DISABLED_BY_USER/ENABLED)
                    // 5. It is currently installed for the calling user
                    // TODO(b/329739019):Support user case that NEW carrier app is added during OTA
                    if (!hasRunEver && !isUpdatedSystemApp(ai) && enabledSetting
                            == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                            && (ai.flags & ApplicationInfo.FLAG_INSTALLED) != 0) {
                        Log.i(TAG, "Update state (" + packageName
                                + "): DISABLED_UNTIL_USED for user " + userId);
                        context.createContextAsUser(UserHandle.of(userId), 0)
                                .getPackageManager()
                                .setSystemAppState(
                                        packageName,
                                        PackageManager.SYSTEM_APP_STATE_UNINSTALLED);
                    }

                    // Associated apps are more brittle, because we can't rely on the distinction
                    // between "default" and "enabled". To account for this, we have two cases:
                    // 1. We've never run before, so we're fine to disable all associated apps.
                    // 2. We've run before, but not on this SDK version, so we will only operate on
                    //    apps with addedInSdk in the range (lastHandledSdk, currentSdk].
                    // Otherwise, don't touch the associated apps.
                    if (associatedAppList != null) {
                        for (AssociatedAppInfo associatedApp : associatedAppList) {
                            boolean allowDisable = !hasRunEver || (!hasRunForSdk
                                    && associatedApp.addedInSdk
                                    != CarrierAssociatedAppEntry.SDK_UNSPECIFIED
                                    && associatedApp.addedInSdk > carrierAppsHandledSdk
                                    && associatedApp.addedInSdk <= Build.VERSION.SDK_INT);
                            int associatedAppEnabledSetting = context
                                    .createContextAsUser(UserHandle.of(userId), 0)
                                    .getPackageManager()
                                    .getApplicationEnabledSetting(
                                            associatedApp.appInfo.packageName);
                            boolean associatedAppInstalled = (associatedApp.appInfo.flags
                                    & ApplicationInfo.FLAG_INSTALLED) != 0;
                            if (DEBUG) {
                                Log.i(TAG, "(!hasPrivileges) associated app "
                                        + associatedApp.appInfo.packageName + ", allowDisable = "
                                        + allowDisable + ", addedInSdk = "
                                        + associatedApp.addedInSdk + ", enabled = "
                                        + associatedAppEnabledSetting + ", installed = "
                                        + associatedAppInstalled);
                            }
                            if (allowDisable
                                    && associatedAppEnabledSetting
                                    == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                                    && associatedAppInstalled) {
                                Log.i(TAG,
                                        "Update associated state ("
                                        + associatedApp.appInfo.packageName
                                        + "): DISABLED_UNTIL_USED for user " + userId);
                                context.createContextAsUser(UserHandle.of(userId), 0)
                                        .getPackageManager()
                                        .setSystemAppState(associatedApp.appInfo.packageName,
                                                PackageManager.SYSTEM_APP_STATE_UNINSTALLED);
                            }
                        }
                    }
                }
            }

            // Mark the execution so we do not disable apps again on this SDK version.
            if (!hasRunEver || !hasRunForSdk) {
                Settings.Secure.putIntForUser(contentResolver, Settings.Secure.CARRIER_APPS_HANDLED,
                        Build.VERSION.SDK_INT, contentResolver.getUserId());
            }

            if (!enabledCarrierPackages.isEmpty()) {
                // Since we enabled at least one app, ensure we grant default permissions to those
                // apps.
                String[] packageNames = new String[enabledCarrierPackages.size()];
                enabledCarrierPackages.toArray(packageNames);
                permissionManager.grantDefaultPermissionsToEnabledCarrierApps(packageNames,
                        UserHandle.of(userId), TelephonyUtils.DIRECT_EXECUTOR, isSuccess -> { });
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Could not reach PackageManager", e);
        }
    }

    /**
     * Returns the list of "default" carrier apps.
     *
     * This is the subset of apps returned by
     * {@link #getDefaultCarrierAppCandidates(int, Context)} which currently have carrier
     * privileges per the SIM(s) inserted in the device.
     */
    public static List<ApplicationInfo> getDefaultCarrierApps(
            TelephonyManager telephonyManager, int userId, Context context) {
        // Get all system apps from the default list.
        List<ApplicationInfo> candidates = getDefaultCarrierAppCandidates(userId, context);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        // Filter out apps without carrier privileges.
        // Iterate from the end to avoid creating an Iterator object and because we will be removing
        // elements from the list as we pass through it.
        for (int i = candidates.size() - 1; i >= 0; i--) {
            ApplicationInfo ai = candidates.get(i);
            String packageName = ai.packageName;
            boolean hasPrivileges =
                    telephonyManager.checkCarrierPrivilegesForPackageAnyPhone(packageName)
                            == TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS;
            if (!hasPrivileges) {
                candidates.remove(i);
            }
        }

        return candidates;
    }

    /**
     * Returns the list of "default" carrier app candidates.
     *
     * These are the apps subject to the hiding/showing logic in
     * {@link CarrierAppUtils#disableCarrierAppsUntilPrivileged(String, TelephonyManager, int,
     * Context)}, as well as the apps which should have default
     * permissions granted, when a matching SIM is inserted.
     *
     * Whether or not the app is actually considered a default app depends on whether the app has
     * carrier privileges as determined by the SIMs in the device.
     */
    public static List<ApplicationInfo> getDefaultCarrierAppCandidates(
            int userId, Context context) {
        Set<String> systemCarrierAppsDisabledUntilUsed =
                context.getSystemService(SystemConfigManager.class)
                        .getDisabledUntilUsedPreinstalledCarrierApps();
        return getDefaultCarrierAppCandidatesHelper(userId, systemCarrierAppsDisabledUntilUsed,
                context);
    }

    private static List<ApplicationInfo> getDefaultCarrierAppCandidatesHelper(
            int userId, Set<String> systemCarrierAppsDisabledUntilUsed, Context context) {
        if (systemCarrierAppsDisabledUntilUsed == null
                || systemCarrierAppsDisabledUntilUsed.isEmpty()) {
            return null;
        }

        List<ApplicationInfo> apps = new ArrayList<>(systemCarrierAppsDisabledUntilUsed.size());
        for (String packageName : systemCarrierAppsDisabledUntilUsed) {
            ApplicationInfo ai =
                    getApplicationInfoIfSystemApp(userId, packageName, context);
            if (ai != null) {
                apps.add(ai);
            }
        }
        return apps;
    }

    private static Map<String, List<AssociatedAppInfo>> getDefaultCarrierAssociatedAppsHelper(
            int userId, Map<String, List<CarrierAssociatedAppEntry>>
            systemCarrierAssociatedAppsDisabledUntilUsed, Context context) {
        int size = systemCarrierAssociatedAppsDisabledUntilUsed.size();
        Map<String, List<AssociatedAppInfo>> associatedApps = new ArrayMap<>(size);
        for (Map.Entry<String, List<CarrierAssociatedAppEntry>> entry
                : systemCarrierAssociatedAppsDisabledUntilUsed.entrySet()) {
            String carrierAppPackage = entry.getKey();
            List<CarrierAssociatedAppEntry> associatedAppPackages = entry.getValue();
            for (int j = 0; j < associatedAppPackages.size(); j++) {
                CarrierAssociatedAppEntry associatedApp = associatedAppPackages.get(j);
                ApplicationInfo ai =
                        getApplicationInfoIfSystemApp(userId, associatedApp.packageName, context);
                // Only update enabled state for the app on /system. Once it has been updated we
                // shouldn't touch it.
                if (ai != null && !isUpdatedSystemApp(ai)) {
                    List<AssociatedAppInfo> appList = associatedApps.get(carrierAppPackage);
                    if (appList == null) {
                        appList = new ArrayList<>();
                        associatedApps.put(carrierAppPackage, appList);
                    }
                    appList.add(new AssociatedAppInfo(ai, associatedApp.addedInSdk));
                }
            }
        }
        return associatedApps;
    }

    @Nullable
    private static ApplicationInfo getApplicationInfoIfSystemApp(
            int userId, String packageName, Context context) {
        try {
            ApplicationInfo ai = context.createContextAsUser(UserHandle.of(userId), 0)
                    .getPackageManager()
                    .getApplicationInfo(packageName,
                            PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
                                    | PackageManager.MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS
                                    | PackageManager.MATCH_SYSTEM_ONLY);
            if (ai != null) {
                return ai;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Could not reach PackageManager", e);
        }
        return null;
    }

    private static final class AssociatedAppInfo {
        public final ApplicationInfo appInfo;
        // Might be CarrierAssociatedAppEntry.SDK_UNSPECIFIED.
        public final int addedInSdk;

        AssociatedAppInfo(ApplicationInfo appInfo, int addedInSdk) {
            this.appInfo = appInfo;
            this.addedInSdk = addedInSdk;
        }
    }
}
