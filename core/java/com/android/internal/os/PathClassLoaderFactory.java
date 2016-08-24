/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.os.Trace;

import dalvik.system.PathClassLoader;

/**
 * Creates path class loaders.
 *
 * @hide
 */
public class PathClassLoaderFactory {
    // Unconstructable
    private PathClassLoaderFactory() {}

    /**
     * Create a PathClassLoader and initialize a linker-namespace for it.
     *
     * @hide
     */
    public static PathClassLoader createClassLoader(String dexPath,
                                                    String librarySearchPath,
                                                    String libraryPermittedPath,
                                                    ClassLoader parent,
                                                    int targetSdkVersion,
                                                    boolean isNamespaceShared) {
        PathClassLoader pathClassloader = new PathClassLoader(dexPath, librarySearchPath, parent);

        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "createClassloaderNamespace");
        String errorMessage = createClassloaderNamespace(pathClassloader,
                                                         targetSdkVersion,
                                                         librarySearchPath,
                                                         libraryPermittedPath,
                                                         isNamespaceShared);
        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);

        if (errorMessage != null) {
            throw new UnsatisfiedLinkError("Unable to create namespace for the classloader " +
                                           pathClassloader + ": " + errorMessage);
        }

        return pathClassloader;
    }

    private static native String createClassloaderNamespace(ClassLoader classLoader,
                                                            int targetSdkVersion,
                                                            String librarySearchPath,
                                                            String libraryPermittedPath,
                                                            boolean isNamespaceShared);
}
