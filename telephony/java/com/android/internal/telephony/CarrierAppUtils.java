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
 * limitations under the License
 */

package com.android.internal.telephony;

import android.annotation.Nullable;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.RemoteException;
import android.telephony.TelephonyManager;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

/**
 * Utilities for handling carrier applications.
 * @hide
 */
public final class CarrierAppUtils {
    private static final String TAG = "CarrierAppUtils";

    private static final boolean DEBUG = false; // STOPSHIP if true

    private CarrierAppUtils() {}

    /**
     * Handle preinstalled carrier apps which should be disabled until a matching SIM is inserted.
     *
     * Evaluates the list of applications in config_disabledUntilUsedPreinstalledCarrierApps. We
     * want to disable each such application which is present on the system image until the user
     * inserts a SIM which causes that application to gain carrier privilege (indicating a "match"),
     * without interfering with the user if they opt to enable/disable the app explicitly.
     *
     * So, for each such app, we either disable until used IFF the app is not carrier privileged AND
     * in the default state (e.g. not explicitly DISABLED/DISABLED_BY_USER/ENABLED), or we enable if
     * the app is carrier privileged and in either the default state or DISABLED_UNTIL_USED.
     *
     * When enabling a carrier app we also grant it default permissions.
     *
     * This method is idempotent and is safe to be called at any time; it should be called once at
     * system startup prior to any application running, as well as any time the set of carrier
     * privileged apps may have changed.
     */
    public synchronized static void disableCarrierAppsUntilPrivileged(String callingPackage,
            IPackageManager packageManager, TelephonyManager telephonyManager, int userId) {
        if (DEBUG) {
            Slog.d(TAG, "disableCarrierAppsUntilPrivileged");
        }
        String[] systemCarrierAppsDisabledUntilUsed = Resources.getSystem().getStringArray(
                com.android.internal.R.array.config_disabledUntilUsedPreinstalledCarrierApps);
        disableCarrierAppsUntilPrivileged(callingPackage, packageManager, telephonyManager, userId,
                systemCarrierAppsDisabledUntilUsed);
    }

    /**
     * Like {@link #disableCarrierAppsUntilPrivileged(String, IPackageManager, TelephonyManager,
     * int)}, but assumes that no carrier apps have carrier privileges.
     *
     * This prevents a potential race condition on first boot - since the app's default state is
     * enabled, we will initially disable it when the telephony stack is first initialized as it has
     * not yet read the carrier privilege rules. However, since telephony is initialized later on
     * late in boot, the app being disabled may have already been started in response to certain
     * broadcasts. The app will continue to run (briefly) after being disabled, before the Package
     * Manager can kill it, and this can lead to crashes as the app is in an unexpected state.
     */
    public synchronized static void disableCarrierAppsUntilPrivileged(String callingPackage,
            IPackageManager packageManager, int userId) {
        if (DEBUG) {
            Slog.d(TAG, "disableCarrierAppsUntilPrivileged");
        }
        String[] systemCarrierAppsDisabledUntilUsed = Resources.getSystem().getStringArray(
                com.android.internal.R.array.config_disabledUntilUsedPreinstalledCarrierApps);
        disableCarrierAppsUntilPrivileged(callingPackage, packageManager,
                null /* telephonyManager */, userId, systemCarrierAppsDisabledUntilUsed);
    }

    // Must be public b/c framework unit tests can't access package-private methods.
    @VisibleForTesting
    public static void disableCarrierAppsUntilPrivileged(String callingPackage,
            IPackageManager packageManager, @Nullable TelephonyManager telephonyManager, int userId,
            String[] systemCarrierAppsDisabledUntilUsed) {
        List<ApplicationInfo> candidates = getDefaultCarrierAppCandidatesHelper(packageManager,
                userId, systemCarrierAppsDisabledUntilUsed);
        if (candidates == null || candidates.isEmpty()) {
            return;
        }

        List<String> enabledCarrierPackages = new ArrayList<>();

        try {
            for (ApplicationInfo ai : candidates) {
                String packageName = ai.packageName;
                boolean hasPrivileges = telephonyManager != null &&
                        telephonyManager.checkCarrierPrivilegesForPackageAnyPhone(packageName) ==
                                TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS;

                // Only update enabled state for the app on /system. Once it has been updated we
                // shouldn't touch it.
                if (!ai.isUpdatedSystemApp()) {
                    if (hasPrivileges
                            && (ai.enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                            || ai.enabledSetting ==
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)) {
                        Slog.i(TAG, "Update state(" + packageName + "): ENABLED for user "
                                + userId);
                        packageManager.setApplicationEnabledSetting(packageName,
                                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                                PackageManager.DONT_KILL_APP, userId, callingPackage);
                    } else if (!hasPrivileges
                            && ai.enabledSetting ==
                            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                        Slog.i(TAG, "Update state(" + packageName
                                + "): DISABLED_UNTIL_USED for user " + userId);
                        packageManager.setApplicationEnabledSetting(packageName,
                                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED, 0,
                                userId, callingPackage);
                    }
                }

                // Always re-grant default permissions to carrier apps w/ privileges.
                if (hasPrivileges) {
                    enabledCarrierPackages.add(ai.packageName);
                }
            }

            if (!enabledCarrierPackages.isEmpty()) {
                // Since we enabled at least one app, ensure we grant default permissions to those
                // apps.
                String[] packageNames = new String[enabledCarrierPackages.size()];
                enabledCarrierPackages.toArray(packageNames);
                packageManager.grantDefaultPermissionsToEnabledCarrierApps(packageNames, userId);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "Could not reach PackageManager", e);
        }
    }

    /**
     * Returns the list of "default" carrier apps.
     *
     * This is the subset of apps returned by
     * {@link #getDefaultCarrierAppCandidates(IPackageManager, int)} which currently have carrier
     * privileges per the SIM(s) inserted in the device.
     */
    public static List<ApplicationInfo> getDefaultCarrierApps(IPackageManager packageManager,
            TelephonyManager telephonyManager, int userId) {
        // Get all system apps from the default list.
        List<ApplicationInfo> candidates = getDefaultCarrierAppCandidates(packageManager, userId);
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
                    telephonyManager.checkCarrierPrivilegesForPackageAnyPhone(packageName) ==
                            TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS;
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
     * {@link CarrierAppUtils#disableCarrierAppsUntilPrivileged(String, IPackageManager,
     * TelephonyManager, int)}, as well as the apps which should have default permissions granted,
     * when a matching SIM is inserted.
     *
     * Whether or not the app is actually considered a default app depends on whether the app has
     * carrier privileges as determined by the SIMs in the device.
     */
    public static List<ApplicationInfo> getDefaultCarrierAppCandidates(
            IPackageManager packageManager, int userId) {
        String[] systemCarrierAppsDisabledUntilUsed = Resources.getSystem().getStringArray(
                com.android.internal.R.array.config_disabledUntilUsedPreinstalledCarrierApps);
        return getDefaultCarrierAppCandidatesHelper(packageManager, userId,
                systemCarrierAppsDisabledUntilUsed);
    }

    private static List<ApplicationInfo> getDefaultCarrierAppCandidatesHelper(
            IPackageManager packageManager, int userId,
            String[] systemCarrierAppsDisabledUntilUsed) {
        if (systemCarrierAppsDisabledUntilUsed == null
                || systemCarrierAppsDisabledUntilUsed.length == 0) {
            return null;
        }
        List<ApplicationInfo> apps = null;
        try {
            apps = new ArrayList<>(systemCarrierAppsDisabledUntilUsed.length);
            for (String packageName : systemCarrierAppsDisabledUntilUsed) {
                ApplicationInfo ai = packageManager.getApplicationInfo(packageName,
                        PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS, userId);
                if (ai == null) {
                    // No app found for packageName
                    continue;
                }
                if (!ai.isSystemApp()) {
                    continue;
                }
                apps.add(ai);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "Could not reach PackageManager", e);
        }
        return apps;
    }
}
