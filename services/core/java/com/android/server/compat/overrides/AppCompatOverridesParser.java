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

package com.android.server.compat.overrides;

import static android.content.pm.PackageManager.MATCH_ANY_USER;

import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;

import android.app.compat.PackageOverride;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.KeyValueListParser;
import android.util.Slog;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A utility class for parsing App Compat Overrides flags.
 *
 * @hide
 */
final class AppCompatOverridesParser {
    /**
     * Flag for specifying all compat change IDs owned by a namespace. See {@link
     * #parseOwnedChangeIds} for information on how this flag is parsed.
     */
    static final String FLAG_OWNED_CHANGE_IDS = "owned_change_ids";

    /**
     * Flag for immediately removing overrides for certain packages and change IDs (from the compat
     * platform), as well as stopping to apply them, in case of an emergency. See {@link
     * #parseRemoveOverrides} for information on how this flag is parsed.
     */
    static final String FLAG_REMOVE_OVERRIDES = "remove_overrides";

    private static final String TAG = "AppCompatOverridesParser";

    private static final String WILDCARD_SYMBOL = "*";

    private static final Pattern BOOLEAN_PATTERN =
            Pattern.compile("true|false", Pattern.CASE_INSENSITIVE);

    private static final String WILDCARD_NO_OWNED_CHANGE_IDS_WARNING =
            "Wildcard can't be used in '" + FLAG_REMOVE_OVERRIDES + "' flag with an empty "
                    + FLAG_OWNED_CHANGE_IDS + "' flag";

    private final PackageManager mPackageManager;

    AppCompatOverridesParser(PackageManager packageManager) {
        mPackageManager = packageManager;
    }

    /**
     * Parses the given {@code configStr} and returns a map from package name to a set of change
     * IDs to remove for that package.
     *
     * <p>The given {@code configStr} is expected to either be:
     *
     * <ul>
     *   <li>'*' (wildcard), to indicate that all owned overrides, specified in {@code
     *   ownedChangeIds}, for all installed packages should be removed.
     *   <li>A comma separated key value list, where the key is a package name and the value is
     *       either:
     *       <ul>
     *         <li>'*' (wildcard), to indicate that all owned overrides, specified in {@code
     *         ownedChangeIds} for that package should be removed.
     *         <li>A colon separated list of change IDs to remove for that package.
     *       </ul>
     * </ul>
     *
     * <p>If the given {@code configStr} doesn't match the expected format, an empty map will be
     * returned. If a specific change ID isn't a valid long, it will be ignored.
     */
    Map<String, Set<Long>> parseRemoveOverrides(String configStr, Set<Long> ownedChangeIds) {
        if (configStr.isEmpty()) {
            return emptyMap();
        }

        Map<String, Set<Long>> result = new ArrayMap<>();
        if (configStr.equals(WILDCARD_SYMBOL)) {
            if (ownedChangeIds.isEmpty()) {
                Slog.w(TAG, WILDCARD_NO_OWNED_CHANGE_IDS_WARNING);
                return emptyMap();
            }
            List<ApplicationInfo> installedApps = mPackageManager.getInstalledApplications(
                    MATCH_ANY_USER);
            for (ApplicationInfo appInfo : installedApps) {
                result.put(appInfo.packageName, ownedChangeIds);
            }
            return result;
        }

        KeyValueListParser parser = new KeyValueListParser(',');
        try {
            parser.setString(configStr);
        } catch (IllegalArgumentException e) {
            Slog.w(
                    TAG,
                    "Invalid format in '" + FLAG_REMOVE_OVERRIDES + "' flag: " + configStr, e);
            return emptyMap();
        }
        for (int i = 0; i < parser.size(); i++) {
            String packageName = parser.keyAt(i);
            String changeIdsStr = parser.getString(packageName, /* def= */ "");
            if (changeIdsStr.equals(WILDCARD_SYMBOL)) {
                if (ownedChangeIds.isEmpty()) {
                    Slog.w(TAG, WILDCARD_NO_OWNED_CHANGE_IDS_WARNING);
                    continue;
                }
                result.put(packageName, ownedChangeIds);
            } else {
                for (String changeIdStr : changeIdsStr.split(":")) {
                    try {
                        long changeId = Long.parseLong(changeIdStr);
                        result.computeIfAbsent(packageName, k -> new ArraySet<>()).add(changeId);
                    } catch (NumberFormatException e) {
                        Slog.w(
                                TAG,
                                "Invalid change ID in '" + FLAG_REMOVE_OVERRIDES + "' flag: "
                                        + changeIdStr, e);
                    }
                }
            }
        }

        return result;
    }


    /**
     * Parses the given {@code configStr}, that is expected to be a comma separated list of change
     * IDs, into a set.
     *
     * <p>If any of the change IDs isn't a valid long, it will be ignored.
     */
    static Set<Long> parseOwnedChangeIds(String configStr) {
        if (configStr.isEmpty()) {
            return emptySet();
        }

        Set<Long> result = new ArraySet<>();
        for (String changeIdStr : configStr.split(",")) {
            try {
                result.add(Long.parseLong(changeIdStr));
            } catch (NumberFormatException e) {
                Slog.w(TAG,
                        "Invalid change ID in '" + FLAG_OWNED_CHANGE_IDS + "' flag: " + changeIdStr,
                        e);
            }
        }
        return result;
    }

    /**
     * Parses the given {@code configStr}, that is expected to be a comma separated list of changes
     * overrides, and returns a map from change ID to {@link PackageOverride} instances to add.
     *
     * <p>Each change override is in the following format:
     * '<change-id>:<min-version-code?>:<max-version-code?>:<enabled>'.
     *
     * <p>If there are multiple overrides that should be added with the same change ID, the one
     * that best fits the given {@code versionCode} is added.
     *
     * <p>Any overrides whose change ID is in {@code changeIdsToSkip} are ignored.
     *
     * <p>If a change override entry in {@code configStr} is invalid, it will be ignored.
     */
    static Map<Long, PackageOverride> parsePackageOverrides(String configStr, long versionCode,
            Set<Long> changeIdsToSkip) {
        if (configStr.isEmpty()) {
            return emptyMap();
        }
        PackageOverrideComparator comparator = new PackageOverrideComparator(versionCode);
        Map<Long, PackageOverride> overridesToAdd = new ArrayMap<>();
        for (String overrideEntryString : configStr.split(",")) {
            List<String> changeIdAndVersions = Arrays.asList(overrideEntryString.split(":", 4));
            if (changeIdAndVersions.size() != 4) {
                Slog.w(TAG, "Invalid change override entry: " + overrideEntryString);
                continue;
            }
            long changeId;
            try {
                changeId = Long.parseLong(changeIdAndVersions.get(0));
            } catch (NumberFormatException e) {
                Slog.w(TAG, "Invalid change ID in override entry: " + overrideEntryString, e);
                continue;
            }

            if (changeIdsToSkip.contains(changeId)) {
                continue;
            }

            String minVersionCodeStr = changeIdAndVersions.get(1);
            String maxVersionCodeStr = changeIdAndVersions.get(2);

            String enabledStr = changeIdAndVersions.get(3);
            if (!BOOLEAN_PATTERN.matcher(enabledStr).matches()) {
                Slog.w(TAG, "Invalid enabled string in override entry: " + overrideEntryString);
                continue;
            }
            boolean enabled = Boolean.parseBoolean(enabledStr);
            PackageOverride.Builder overrideBuilder = new PackageOverride.Builder().setEnabled(
                    enabled);
            try {
                if (!minVersionCodeStr.isEmpty()) {
                    overrideBuilder.setMinVersionCode(Long.parseLong(minVersionCodeStr));
                }
                if (!maxVersionCodeStr.isEmpty()) {
                    overrideBuilder.setMaxVersionCode(Long.parseLong(maxVersionCodeStr));
                }
            } catch (NumberFormatException e) {
                Slog.w(TAG,
                        "Invalid min/max version code in override entry: " + overrideEntryString,
                        e);
                continue;
            }

            try {
                PackageOverride override = overrideBuilder.build();
                if (!overridesToAdd.containsKey(changeId)
                        || comparator.compare(override, overridesToAdd.get(changeId)) < 0) {
                    overridesToAdd.put(changeId, override);
                }
            } catch (IllegalArgumentException e) {
                Slog.w(TAG, "Failed to build PackageOverride", e);
            }
        }

        return overridesToAdd;
    }

    /**
     * A {@link Comparator} that compares @link PackageOverride} instances with respect to a
     * specified {@code versionCode} as follows:
     *
     * <ul>
     *   <li>Prefer the {@link PackageOverride} whose version range contains {@code versionCode}.
     *   <li>Otherwise, prefer the {@link PackageOverride} whose version range is closest to {@code
     *       versionCode} from below.
     *   <li>Otherwise, prefer the {@link PackageOverride} whose version range is closest to {@code
     *       versionCode} from above.
     * </ul>
     */
    private static final class PackageOverrideComparator implements Comparator<PackageOverride> {
        private final long mVersionCode;

        PackageOverrideComparator(long versionCode) {
            this.mVersionCode = versionCode;
        }

        @Override
        public int compare(PackageOverride o1, PackageOverride o2) {
            // Prefer overrides whose version range contains versionCode.
            boolean isVersionInRange1 = isVersionInRange(o1, mVersionCode);
            boolean isVersionInRange2 = isVersionInRange(o2, mVersionCode);
            if (isVersionInRange1 != isVersionInRange2) {
                return isVersionInRange1 ? -1 : 1;
            }

            // Otherwise, prefer overrides whose version range is before versionCode.
            boolean isVersionAfterRange1 = isVersionAfterRange(o1, mVersionCode);
            boolean isVersionAfterRange2 = isVersionAfterRange(o2, mVersionCode);
            if (isVersionAfterRange1 != isVersionAfterRange2) {
                return isVersionAfterRange1 ? -1 : 1;
            }

            // If both overrides' version ranges are either before or after versionCode, prefer
            // those whose version range is closer to versionCode.
            return Long.compare(
                    getVersionProximity(o1, mVersionCode), getVersionProximity(o2, mVersionCode));
        }

        /**
         * Returns true if the version range in the given {@code override} contains {@code
         * versionCode}.
         */
        private static boolean isVersionInRange(PackageOverride override, long versionCode) {
            return override.getMinVersionCode() <= versionCode
                    && versionCode <= override.getMaxVersionCode();
        }

        /**
         * Returns true if the given {@code versionCode} is strictly after the version range in the
         * given {@code override}.
         */
        private static boolean isVersionAfterRange(PackageOverride override, long versionCode) {
            return override.getMaxVersionCode() < versionCode;
        }

        /**
         * Returns true if the given {@code versionCode} is strictly before the version range in the
         * given {@code override}.
         */
        private static boolean isVersionBeforeRange(PackageOverride override, long versionCode) {
            return override.getMinVersionCode() > versionCode;
        }

        /**
         * In case the given {@code versionCode} is strictly before or after the version range in
         * the given {@code override}, returns the distance from it, otherwise returns zero.
         */
        private static long getVersionProximity(PackageOverride override, long versionCode) {
            if (isVersionAfterRange(override, versionCode)) {
                return versionCode - override.getMaxVersionCode();
            }
            if (isVersionBeforeRange(override, versionCode)) {
                return override.getMinVersionCode() - versionCode;
            }

            // Version is in range. Note that when two overrides have a zero version proximity
            // they will be ordered arbitrarily.
            return 0;
        }
    }
}
