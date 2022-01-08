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

package com.android.server.pm;

import android.annotation.Nullable;
import android.content.pm.SharedLibraryInfo;

import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.utils.WatchedLongSparseArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SharedLibraryUtils {

    /**
     * Returns false if the adding shared library already exists in the map and so could not be
     * added.
     */
    public static boolean addSharedLibraryToPackageVersionMap(
            Map<String, WatchedLongSparseArray<SharedLibraryInfo>> target,
            SharedLibraryInfo library) {
        final String name = library.getName();
        if (target.containsKey(name)) {
            if (library.getType() != SharedLibraryInfo.TYPE_STATIC) {
                // We've already added this non-version-specific library to the map.
                return false;
            } else if (target.get(name).indexOfKey(library.getLongVersion()) >= 0) {
                // We've already added this version of a version-specific library to the map.
                return false;
            }
        } else {
            target.put(name, new WatchedLongSparseArray<>());
        }
        target.get(name).put(library.getLongVersion(), library);
        return true;
    }

    @Nullable
    public static SharedLibraryInfo getSharedLibraryInfo(String name, long version,
            Map<String, WatchedLongSparseArray<SharedLibraryInfo>> existingLibraries,
            @Nullable Map<String, WatchedLongSparseArray<SharedLibraryInfo>> newLibraries) {
        if (newLibraries != null) {
            final WatchedLongSparseArray<SharedLibraryInfo> versionedLib = newLibraries.get(name);
            SharedLibraryInfo info = null;
            if (versionedLib != null) {
                info = versionedLib.get(version);
            }
            if (info != null) {
                return info;
            }
        }
        final WatchedLongSparseArray<SharedLibraryInfo> versionedLib = existingLibraries.get(name);
        if (versionedLib == null) {
            return null;
        }
        return versionedLib.get(version);
    }

    public static List<SharedLibraryInfo> findSharedLibraries(PackageStateInternal pkgSetting) {
        if (!pkgSetting.getTransientState().getUsesLibraryInfos().isEmpty()) {
            ArrayList<SharedLibraryInfo> retValue = new ArrayList<>();
            Set<String> collectedNames = new HashSet<>();
            for (SharedLibraryInfo info : pkgSetting.getTransientState().getUsesLibraryInfos()) {
                findSharedLibrariesRecursive(info, retValue, collectedNames);
            }
            return retValue;
        } else {
            return Collections.emptyList();
        }
    }

    private static void findSharedLibrariesRecursive(SharedLibraryInfo info,
            ArrayList<SharedLibraryInfo> collected, Set<String> collectedNames) {
        if (!collectedNames.contains(info.getName())) {
            collectedNames.add(info.getName());
            collected.add(info);

            if (info.getDependencies() != null) {
                for (SharedLibraryInfo dep : info.getDependencies()) {
                    findSharedLibrariesRecursive(dep, collected, collectedNames);
                }
            }
        }
    }

}
