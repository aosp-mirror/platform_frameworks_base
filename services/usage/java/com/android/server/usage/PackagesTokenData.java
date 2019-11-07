/*
 * Copyright 2019 The Android Open Source Project
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
package com.android.server.usage;

import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;

import java.util.ArrayList;

/**
 * An object holding data defining the obfuscated packages and their token mappings.
 * Used by {@link UsageStatsDatabase}.
 *
 * @hide
 */
public final class PackagesTokenData {
    /**
     * The package name is always stored at index 0 in {@code tokensToPackagesMap}.
     */
    private static final int PACKAGE_NAME_INDEX = 0;

    /**
     * The default token for any string that hasn't been tokenized yet.
     */
    public static final int UNASSIGNED_TOKEN = -1;

    /**
     * The main token counter for each package.
     */
    public int counter = 1;
    /**
     * Stores a hierarchy of token to string mappings for each package, indexed by the main
     * package token. The 0th index within the array list will always hold the package name.
     */
    public final SparseArray<ArrayList<String>> tokensToPackagesMap = new SparseArray<>();
    /**
     * Stores a hierarchy of strings to token mappings for each package. This is simply an inverse
     * map of the {@code tokenToPackagesMap} in this class, mainly for an O(1) access to the tokens.
     */
    public final ArrayMap<String, ArrayMap<String, Integer>> packagesToTokensMap = new ArrayMap<>();
    /**
     * Stores a map of packages that were removed and when they were removed.
     */
    public final ArrayMap<String, Long> removedPackagesMap = new ArrayMap<>();

    public PackagesTokenData() {
    }

    /**
     * Fetches the token mapped to the given package name. If there is no mapping, a new token is
     * created and the relevant mappings are updated.
     *
     * @param packageName the package name whose token is being fetched
     * @param timeStamp the time stamp of the event or end time of the usage stats; used to verify
     *                  the package hasn't been removed
     * @return the mapped token
     */
    public int getPackageTokenOrAdd(String packageName, long timeStamp) {
        final Long timeRemoved = removedPackagesMap.get(packageName);
        if (timeRemoved != null && timeRemoved > timeStamp) {
            return UNASSIGNED_TOKEN; // package was removed
            /*
             Note: instead of querying Package Manager each time for a list of packages to verify
             if this package is still installed, it's more efficient to check the internal list of
             removed packages and verify with the incoming time stamp. Although rare, it is possible
             that some asynchronous function is triggered after a package is removed and the
             time stamp passed into this function is not accurate. We'll have to keep the respective
             event/usage stat until the next time the device reboots and the mappings are cleaned.
             Additionally, this is a data class with some helper methods - it doesn't make sense to
             overload it with references to other services.
             */
        }

        ArrayMap<String, Integer> packageTokensMap = packagesToTokensMap.get(packageName);
        if (packageTokensMap == null) {
            packageTokensMap = new ArrayMap<>();
            packagesToTokensMap.put(packageName, packageTokensMap);
        }
        int token = packageTokensMap.getOrDefault(packageName, UNASSIGNED_TOKEN);
        if (token == UNASSIGNED_TOKEN) {
            token = counter++;
            // package name should always be at index 0 in the sub-mapping
            ArrayList<String> tokenPackages = new ArrayList<>();
            tokenPackages.add(packageName);
            packageTokensMap.put(packageName, token);
            tokensToPackagesMap.put(token, tokenPackages);
        }
        return token;
    }

    /**
     * Fetches the token mapped to the given key within the package's context. If there is no
     * mapping, a new token is created and the relevant mappings are updated.
     *
     * @param packageToken the package token for which the given key belongs to
     * @param packageName the package name for which the given key belongs to
     * @param key the key whose token is being fetched
     * @return the mapped token
     */
    public int getTokenOrAdd(int packageToken, String packageName, String key) {
        if (packageName.equals(key)) {
            return PACKAGE_NAME_INDEX;
        }
        int token = packagesToTokensMap.get(packageName).getOrDefault(key, UNASSIGNED_TOKEN);
        if (token == UNASSIGNED_TOKEN) {
            token = tokensToPackagesMap.get(packageToken).size();
            packagesToTokensMap.get(packageName).put(key, token);
            tokensToPackagesMap.get(packageToken).add(key);
        }
        return token;
    }

    /**
     * Fetches the package name for the given token.
     *
     * @param packageToken the package token representing the package name
     * @return the string representing the given token or {@code null} if not found
     */
    public String getPackageString(int packageToken) {
        final ArrayList<String> packageStrings = tokensToPackagesMap.get(packageToken);
        if (packageStrings == null) {
            return null;
        }
        return packageStrings.get(PACKAGE_NAME_INDEX);
    }

    /**
     * Fetches the string represented by the given token.
     *
     * @param packageToken the package token for which this token belongs to
     * @param token the token whose string needs to be fetched
     * @return the string representing the given token or {@code null} if not found
     */
    public String getString(int packageToken, int token) {
        try {
            return tokensToPackagesMap.get(packageToken).get(token);
        } catch (NullPointerException npe) {
            Slog.e("PackagesTokenData",
                    "Unable to find tokenized strings for package " + packageToken, npe);
            return null;
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
    }

    /**
     * Removes the package from all known mappings.
     *
     * @param packageName the package to be removed
     * @param timeRemoved the time stamp of when the package was removed
     * @return the token mapped to the package removed or {@code PackagesTokenData.UNASSIGNED_TOKEN}
     *         if not mapped
     */
    public int removePackage(String packageName, long timeRemoved) {
        removedPackagesMap.put(packageName, timeRemoved);

        if (!packagesToTokensMap.containsKey(packageName)) {
            return UNASSIGNED_TOKEN;
        }
        final int packageToken = packagesToTokensMap.get(packageName).get(packageName);
        packagesToTokensMap.remove(packageName);
        tokensToPackagesMap.delete(packageToken);
        return packageToken;
    }
}
