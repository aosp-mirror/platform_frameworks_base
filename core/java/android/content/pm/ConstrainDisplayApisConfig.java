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
import android.util.ArrayMap;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
     * Indicates that display APIs should never be constrained to the activity window bounds for all
     * packages.
     */
    private boolean mNeverConstrainDisplayApisAllPackages;

    /**
     * Indicates that display APIs should never be constrained to the activity window bounds for
     * a set of defined packages. Map keys are package names, and entries are a
     * 'Pair(<min-version-code>, <max-version-code>)'.
     */
    private ArrayMap<String, Pair<Long, Long>> mNeverConstrainConfigMap;

    /**
     * Indicates that display APIs should always be constrained to the activity window bounds for
     * a set of defined packages. Map keys are package names, and entries are a
     * 'Pair(<min-version-code>, <max-version-code>)'.
     */
    private ArrayMap<String, Pair<Long, Long>> mAlwaysConstrainConfigMap;

    public ConstrainDisplayApisConfig() {
        updateCache();

        DeviceConfig.addOnPropertiesChangedListener(NAMESPACE_CONSTRAIN_DISPLAY_APIS,
                BackgroundThread.getExecutor(), properties -> updateCache());
    }

    /**
     * Returns true if either the flag 'never_constrain_display_apis_all_packages' is true or the
     * flag 'never_constrain_display_apis' contains a package entry that matches the given {@code
     * applicationInfo}.
     *
     * @param applicationInfo Information about the application/package.
     */
    public boolean getNeverConstrainDisplayApis(ApplicationInfo applicationInfo) {
        if (mNeverConstrainDisplayApisAllPackages) {
            return true;
        }

        return flagHasMatchingPackageEntry(mNeverConstrainConfigMap, applicationInfo);
    }

    /**
     * Returns true if the flag 'always_constrain_display_apis' contains a package entry that
     * matches the given {@code applicationInfo}.
     *
     * @param applicationInfo Information about the application/package.
     */
    public boolean getAlwaysConstrainDisplayApis(ApplicationInfo applicationInfo) {
        return flagHasMatchingPackageEntry(mAlwaysConstrainConfigMap, applicationInfo);
    }


    /**
     * Updates {@link #mNeverConstrainDisplayApisAllPackages}, {@link #mNeverConstrainConfigMap},
     * and {@link #mAlwaysConstrainConfigMap} from the {@link DeviceConfig}.
     */
    private void updateCache() {
        mNeverConstrainDisplayApisAllPackages = DeviceConfig.getBoolean(
                NAMESPACE_CONSTRAIN_DISPLAY_APIS,
                FLAG_NEVER_CONSTRAIN_DISPLAY_APIS_ALL_PACKAGES, /* defaultValue= */ false);

        final String neverConstrainConfigStr = DeviceConfig.getString(
                NAMESPACE_CONSTRAIN_DISPLAY_APIS,
                FLAG_NEVER_CONSTRAIN_DISPLAY_APIS, /* defaultValue= */ "");
        mNeverConstrainConfigMap = buildConfigMap(neverConstrainConfigStr);

        final String alwaysConstrainConfigStr = DeviceConfig.getString(
                NAMESPACE_CONSTRAIN_DISPLAY_APIS,
                FLAG_ALWAYS_CONSTRAIN_DISPLAY_APIS, /* defaultValue= */ "");
        mAlwaysConstrainConfigMap = buildConfigMap(alwaysConstrainConfigStr);
    }

    /**
     * Processes the configuration string into a map of version codes, for the given
     * configuration to be applied to the specified packages. If the given package
     * entry string is invalid, then the map will not contain an entry for the package.
     *
     * @param configStr A configuration string expected to be in the format of a list of package
     *                  entries separated by ','. A package entry expected to be in the format
     *                  '<package-name>:<min-version-code>?:<max-version-code>?'.
     * @return a map of configuration entries, where each key is a package name. Each value is
     * a pair of version codes, in the format 'Pair(<min-version-code>, <max-version-code>)'.
     */
    private static ArrayMap<String, Pair<Long, Long>> buildConfigMap(String configStr) {
        ArrayMap<String, Pair<Long, Long>> configMap = new ArrayMap<>();
        // String#split returns a non-empty array given an empty string.
        if (configStr.isEmpty()) {
            return configMap;
        }
        for (String packageEntryString : configStr.split(",")) {
            List<String> packageAndVersions = Arrays.asList(packageEntryString.split(":", 3));
            if (packageAndVersions.size() != 3) {
                Slog.w(TAG, "Invalid package entry in flag 'never/always_constrain_display_apis': "
                        + packageEntryString);
                // Skip this entry.
                continue;
            }
            String packageName = packageAndVersions.get(0);
            String minVersionCodeStr = packageAndVersions.get(1);
            String maxVersionCodeStr = packageAndVersions.get(2);
            try {
                final long minVersion =
                        minVersionCodeStr.isEmpty() ? Long.MIN_VALUE : Long.parseLong(
                                minVersionCodeStr);
                final long maxVersion =
                        maxVersionCodeStr.isEmpty() ? Long.MAX_VALUE : Long.parseLong(
                                maxVersionCodeStr);
                Pair<Long, Long> minMaxVersionCodes = new Pair<>(minVersion, maxVersion);
                configMap.put(packageName, minMaxVersionCodes);
            } catch (NumberFormatException e) {
                Slog.w(TAG, "Invalid APK version code in package entry: " + packageEntryString);
                // Skip this entry.
            }
        }
        return configMap;
    }

    /**
     * Returns true if the flag with the given {@code flagName} contains a package entry that
     * matches the given {@code applicationInfo}.
     *
     * @param configMap the map representing the current configuration value to examine
     * @param applicationInfo Information about the application/package.
     */
    private static boolean flagHasMatchingPackageEntry(Map<String, Pair<Long, Long>> configMap,
            ApplicationInfo applicationInfo) {
        if (configMap.isEmpty()) {
            return false;
        }
        if (!configMap.containsKey(applicationInfo.packageName)) {
            return false;
        }
        return matchesApplicationInfo(configMap.get(applicationInfo.packageName), applicationInfo);
    }

    /**
     * Parses the given {@code minMaxVersionCodes} and returns true if {@code
     * applicationInfo.longVersionCode} is within the version range in the pair.
     * Returns false otherwise.
     *
     * @param minMaxVersionCodes A pair expected to be in the format
     *                        'Pair(<min-version-code>, <max-version-code>)'.
     * @param applicationInfo Information about the application/package.
     */
    private static boolean matchesApplicationInfo(Pair<Long, Long> minMaxVersionCodes,
            ApplicationInfo applicationInfo) {
        return applicationInfo.longVersionCode >= minMaxVersionCodes.first
                && applicationInfo.longVersionCode <= minMaxVersionCodes.second;
    }
}
