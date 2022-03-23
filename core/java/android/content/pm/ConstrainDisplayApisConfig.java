/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.content.pm;

import static android.provider.DeviceConfig.NAMESPACE_CONSTRAIN_DISPLAY_APIS;

import android.provider.DeviceConfig;
import android.util.Slog;

import java.util.Arrays;
import java.util.List;

/**
 * Class for processing flags in the Device Config namespace 'constrain_display_apis'.
 *
 * @hide
 */
public final class ConstrainDisplayApisConfig {
    private static final String TAG = ConstrainDisplayApisConfig.class.getSimpleName();

    /**
     * A string flag whose value holds a comma separated list of package entries in the format
     * '<package-name>:<min-version-code>?:<max-version-code>?' for which Display APIs should never
     * be constrained.
     */
    private static final String FLAG_NEVER_CONSTRAIN_DISPLAY_APIS = "never_constrain_display_apis";

    /**
     * A boolean flag indicating whether Display APIs should never be constrained for all
     * packages. If true, {@link #FLAG_NEVER_CONSTRAIN_DISPLAY_APIS} is ignored.
     */
    private static final String FLAG_NEVER_CONSTRAIN_DISPLAY_APIS_ALL_PACKAGES =
            "never_constrain_display_apis_all_packages";

    /**
     * A string flag whose value holds a comma separated list of package entries in the format
     * '<package-name>:<min-version-code>?:<max-version-code>?' for which Display APIs should
     * always be constrained.
     */
    private static final String FLAG_ALWAYS_CONSTRAIN_DISPLAY_APIS =
            "always_constrain_display_apis";

    /**
     * Returns true if either the flag 'never_constrain_display_apis_all_packages' is true or the
     * flag 'never_constrain_display_apis' contains a package entry that matches the given {@code
     * applicationInfo}.
     *
     * @param applicationInfo Information about the application/package.
     */
    public static boolean neverConstrainDisplayApis(ApplicationInfo applicationInfo) {
        if (DeviceConfig.getBoolean(NAMESPACE_CONSTRAIN_DISPLAY_APIS,
                FLAG_NEVER_CONSTRAIN_DISPLAY_APIS_ALL_PACKAGES, /* defaultValue= */ false)) {
            return true;
        }

        return flagHasMatchingPackageEntry(FLAG_NEVER_CONSTRAIN_DISPLAY_APIS, applicationInfo);
    }

    /**
     * Returns true if the flag 'always_constrain_display_apis' contains a package entry that
     * matches the given {@code applicationInfo}.
     *
     * @param applicationInfo Information about the application/package.
     */
    public static boolean alwaysConstrainDisplayApis(ApplicationInfo applicationInfo) {
        return flagHasMatchingPackageEntry(FLAG_ALWAYS_CONSTRAIN_DISPLAY_APIS, applicationInfo);
    }

    /**
     * Returns true if the flag with the given {@code flagName} contains a package entry that
     * matches the given {@code applicationInfo}.
     *
     * @param applicationInfo Information about the application/package.
     */
    private static boolean flagHasMatchingPackageEntry(String flagName,
            ApplicationInfo applicationInfo) {
        String configStr = DeviceConfig.getString(NAMESPACE_CONSTRAIN_DISPLAY_APIS,
                flagName, /* defaultValue= */ "");

        // String#split returns a non-empty array given an empty string.
        if (configStr.isEmpty()) {
            return false;
        }

        for (String packageEntryString : configStr.split(",")) {
            if (matchesApplicationInfo(packageEntryString, applicationInfo)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Parses the given {@code packageEntryString} and returns true if {@code
     * applicationInfo.packageName} matches the package name in the config and {@code
     * applicationInfo.longVersionCode} is within the version range in the config.
     *
     * <p>Logs a warning and returns false in case the given {@code packageEntryString} is invalid.
     *
     * @param packageEntryStr A package entry expected to be in the format
     *                        '<package-name>:<min-version-code>?:<max-version-code>?'.
     * @param applicationInfo Information about the application/package.
     */
    private static boolean matchesApplicationInfo(String packageEntryStr,
            ApplicationInfo applicationInfo) {
        List<String> packageAndVersions = Arrays.asList(packageEntryStr.split(":", 3));
        if (packageAndVersions.size() != 3) {
            Slog.w(TAG, "Invalid package entry in flag 'never_constrain_display_apis': "
                    + packageEntryStr);
            return false;
        }
        String packageName = packageAndVersions.get(0);
        String minVersionCodeStr = packageAndVersions.get(1);
        String maxVersionCodeStr = packageAndVersions.get(2);

        if (!packageName.equals(applicationInfo.packageName)) {
            return false;
        }
        long version = applicationInfo.longVersionCode;
        try {
            if (!minVersionCodeStr.isEmpty() && version < Long.parseLong(minVersionCodeStr)) {
                return false;
            }
            if (!maxVersionCodeStr.isEmpty() && version > Long.parseLong(maxVersionCodeStr)) {
                return false;
            }
        } catch (NumberFormatException e) {
            Slog.w(TAG, "Invalid APK version code in package entry: " + packageEntryStr);
            return false;
        }
        return true;
    }
}
