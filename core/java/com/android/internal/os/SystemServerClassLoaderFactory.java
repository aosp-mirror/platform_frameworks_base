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

package com.android.internal.os;

import android.os.Build;
import android.util.ArrayMap;

import dalvik.system.PathClassLoader;

/** @hide */
public final class SystemServerClassLoaderFactory {
    /**
     * Map of paths to PathClassLoader for standalone system server jars.
     */
    private static final ArrayMap<String, PathClassLoader> sLoadedPaths = new ArrayMap<>();

    /**
     * Creates and caches a ClassLoader for the jar at the given path.
     *
     * This method should only be called by ZygoteInit to prefetch jars. For other users, use
     * {@link getOrCreateClassLoader} instead.
     *
     * The parent class loader should always be the system server class loader. Changing it has
     * implications that require discussion with the mainline team.
     *
     * @hide for internal use only
     */
    /* package */ static PathClassLoader createClassLoader(String path, ClassLoader parent) {
        if (sLoadedPaths.containsKey(path)) {
            throw new IllegalStateException("A ClassLoader for " + path + " already exists");
        }
        PathClassLoader pathClassLoader = (PathClassLoader) ClassLoaderFactory.createClassLoader(
                path, /*librarySearchPath=*/null, /*libraryPermittedPath=*/null, parent,
                Build.VERSION.SDK_INT, /*isNamespaceShared=*/true , /*classLoaderName=*/null);
        sLoadedPaths.put(path, pathClassLoader);
        return pathClassLoader;
    }

    /**
     * Returns a cached ClassLoader to be used at runtime for the jar at the given path. Or, creates
     * one if it is not prefetched and is allowed to be created at runtime.
     *
     * The parent class loader should always be the system server class loader. Changing it has
     * implications that require discussion with the mainline team.
     *
     * @hide for internal use only
     */
    public static PathClassLoader getOrCreateClassLoader(
            String path, ClassLoader parent, boolean isTestOnly) {
        PathClassLoader pathClassLoader = sLoadedPaths.get(path);
        if (pathClassLoader != null) {
            return pathClassLoader;
        }
        if (!allowClassLoaderCreation(path, isTestOnly)) {
            throw new RuntimeException("Creating a ClassLoader from " + path + " is not allowed. "
                    + "Please make sure that the jar is listed in "
                    + "`PRODUCT_APEX_STANDALONE_SYSTEM_SERVER_JARS` in the Makefile and added as a "
                    + "`standalone_contents` of a `systemserverclasspath_fragment` in "
                    + "`Android.bp`.");
        }
        return createClassLoader(path, parent);
    }

    /**
     * Returns whether a class loader for the jar is allowed to be created at runtime.
     */
    private static boolean allowClassLoaderCreation(String path, boolean isTestOnly) {
        // Currently, we only enforce prefetching for APEX jars.
        if (!path.startsWith("/apex/")) {
            return true;
        }
        // APEXes for testing only are okay to ignore.
        if (isTestOnly) {
            return true;
        }
        // If system server is being profiled, it's OK to create class loaders anytime.
        if (ZygoteInit.shouldProfileSystemServer()) {
            return true;
        }
        return false;
    }


}
