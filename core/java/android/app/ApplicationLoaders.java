/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.app;

import android.annotation.UnsupportedAppUsage;
import android.content.pm.SharedLibraryInfo;
import android.os.Build;
import android.os.GraphicsEnvironment;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.os.ClassLoaderFactory;

import dalvik.system.PathClassLoader;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** @hide */
public class ApplicationLoaders {
    private static final String TAG = "ApplicationLoaders";

    @UnsupportedAppUsage
    public static ApplicationLoaders getDefault() {
        return gApplicationLoaders;
    }

    ClassLoader getClassLoader(String zip, int targetSdkVersion, boolean isBundled,
                               String librarySearchPath, String libraryPermittedPath,
                               ClassLoader parent, String classLoaderName) {
        return getClassLoaderWithSharedLibraries(zip, targetSdkVersion, isBundled,
                              librarySearchPath, libraryPermittedPath, parent, classLoaderName,
                              null);
    }

    ClassLoader getClassLoaderWithSharedLibraries(
            String zip, int targetSdkVersion, boolean isBundled,
            String librarySearchPath, String libraryPermittedPath,
            ClassLoader parent, String classLoaderName,
            List<ClassLoader> sharedLibraries) {
        // For normal usage the cache key used is the same as the zip path.
        return getClassLoader(zip, targetSdkVersion, isBundled, librarySearchPath,
                              libraryPermittedPath, parent, zip, classLoaderName, sharedLibraries);
    }

    /**
     * Gets a class loader for a shared library. Additional dependent shared libraries are allowed
     * to be specified (sharedLibraries).
     *
     * Additionally, as an optimization, this will return a pre-created ClassLoader if one has
     * been cached by createAndCacheNonBootclasspathSystemClassLoaders.
     */
    ClassLoader getSharedLibraryClassLoaderWithSharedLibraries(String zip, int targetSdkVersion,
            boolean isBundled, String librarySearchPath, String libraryPermittedPath,
            ClassLoader parent, String classLoaderName, List<ClassLoader> sharedLibraries) {
        ClassLoader loader = getCachedNonBootclasspathSystemLib(zip, parent, classLoaderName,
                sharedLibraries);
        if (loader != null) {
            return loader;
        }

        return getClassLoaderWithSharedLibraries(zip, targetSdkVersion, isBundled,
              librarySearchPath, libraryPermittedPath, parent, classLoaderName, sharedLibraries);
    }

    private ClassLoader getClassLoader(String zip, int targetSdkVersion, boolean isBundled,
                                       String librarySearchPath, String libraryPermittedPath,
                                       ClassLoader parent, String cacheKey,
                                       String classLoaderName, List<ClassLoader> sharedLibraries) {
        /*
         * This is the parent we use if they pass "null" in.  In theory
         * this should be the "system" class loader; in practice we
         * don't use that and can happily (and more efficiently) use the
         * bootstrap class loader.
         */
        ClassLoader baseParent = ClassLoader.getSystemClassLoader().getParent();

        synchronized (mLoaders) {
            if (parent == null) {
                parent = baseParent;
            }

            /*
             * If we're one step up from the base class loader, find
             * something in our cache.  Otherwise, we create a whole
             * new ClassLoader for the zip archive.
             */
            if (parent == baseParent) {
                ClassLoader loader = mLoaders.get(cacheKey);
                if (loader != null) {
                    return loader;
                }

                Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, zip);

                ClassLoader classloader = ClassLoaderFactory.createClassLoader(
                        zip,  librarySearchPath, libraryPermittedPath, parent,
                        targetSdkVersion, isBundled, classLoaderName, sharedLibraries);

                Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);

                Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "setLayerPaths");
                GraphicsEnvironment.getInstance().setLayerPaths(
                        classloader, librarySearchPath, libraryPermittedPath);
                Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);

                if (cacheKey != null) {
                    mLoaders.put(cacheKey, classloader);
                }
                return classloader;
            }

            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, zip);
            ClassLoader loader = ClassLoaderFactory.createClassLoader(
                    zip, null, parent, classLoaderName, sharedLibraries);
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
            return loader;
        }
    }

    /**
     * Caches system library class loaders which are not on the bootclasspath but are still used
     * by many system apps.
     *
     * All libraries in the closure of libraries to be loaded must be in libs. A library can
     * only depend on libraries that come before it in the list.
     */
    public void createAndCacheNonBootclasspathSystemClassLoaders(SharedLibraryInfo[] libs) {
        if (mSystemLibsCacheMap != null) {
            throw new IllegalStateException("Already cached.");
        }

        mSystemLibsCacheMap = new HashMap<String, CachedClassLoader>();

        for (SharedLibraryInfo lib : libs) {
            createAndCacheNonBootclasspathSystemClassLoader(lib);
        }
    }

    /**
     * Caches a single non-bootclasspath class loader.
     *
     * All of this library's dependencies must have previously been cached. Otherwise, an exception
     * is thrown.
     */
    private void createAndCacheNonBootclasspathSystemClassLoader(SharedLibraryInfo lib) {
        String path = lib.getPath();
        List<SharedLibraryInfo> dependencies = lib.getDependencies();

        // get cached classloaders for dependencies
        ArrayList<ClassLoader> sharedLibraries = null;
        if (dependencies != null) {
            sharedLibraries = new ArrayList<ClassLoader>(dependencies.size());
            for (SharedLibraryInfo dependency : dependencies) {
                String dependencyPath = dependency.getPath();
                CachedClassLoader cached = mSystemLibsCacheMap.get(dependencyPath);

                if (cached == null) {
                    throw new IllegalStateException("Failed to find dependency " + dependencyPath
                            + " of cachedlibrary " + path);
                }

                sharedLibraries.add(cached.loader);
            }
        }

        // assume cached libraries work with current sdk since they are built-in
        ClassLoader classLoader = getClassLoader(path, Build.VERSION.SDK_INT, true /*isBundled*/,
                null /*librarySearchPath*/, null /*libraryPermittedPath*/, null /*parent*/,
                null /*cacheKey*/, null /*classLoaderName*/, sharedLibraries /*sharedLibraries*/);

        if (classLoader == null) {
            // bad configuration or break in classloading code
            throw new IllegalStateException("Failed to cache " + path);
        }

        CachedClassLoader cached = new CachedClassLoader();
        cached.loader = classLoader;
        cached.sharedLibraries = sharedLibraries;

        Log.d(TAG, "Created zygote-cached class loader: " + path);
        mSystemLibsCacheMap.put(path, cached);
    }

    private static boolean sharedLibrariesEquals(List<ClassLoader> lhs, List<ClassLoader> rhs) {
        if (lhs == null) {
            return rhs == null;
        }

        return lhs.equals(rhs);
    }

    /**
     * Returns lib cached with createAndCacheNonBootclasspathSystemClassLoader. This is called by
     * the zygote during caching.
     *
     * If there is an error or the cache is not available, this returns null.
     */
    public ClassLoader getCachedNonBootclasspathSystemLib(String zip, ClassLoader parent,
            String classLoaderName, List<ClassLoader> sharedLibraries) {
        if (mSystemLibsCacheMap == null) {
            return null;
        }

        // we only cache top-level libs with the default class loader
        if (parent != null || classLoaderName != null) {
            return null;
        }

        CachedClassLoader cached = mSystemLibsCacheMap.get(zip);
        if (cached == null) {
            return null;
        }

        // cached must be built and loaded in the same environment
        if (!sharedLibrariesEquals(sharedLibraries, cached.sharedLibraries)) {
            Log.w(TAG, "Unexpected environment for cached library: (" + sharedLibraries + "|"
                    + cached.sharedLibraries + ")");
            return null;
        }

        Log.d(TAG, "Returning zygote-cached class loader: " + zip);
        return cached.loader;
    }

    /**
     * Creates a classloader for the WebView APK and places it in the cache of loaders maintained
     * by this class. This is used in the WebView zygote, where its presence in the cache speeds up
     * startup and enables memory sharing.
     */
    public ClassLoader createAndCacheWebViewClassLoader(String packagePath, String libsPath,
                                                        String cacheKey) {
        // The correct paths are calculated by WebViewZygote in the system server and passed to
        // us here. We hardcode the other parameters: WebView always targets the current SDK,
        // does not need to use non-public system libraries, and uses the base classloader as its
        // parent to permit usage of the cache.
        // The cache key is passed separately to enable the stub WebView to be cached under the
        // stub's APK path, when the actual package path is the donor APK.
        return getClassLoader(packagePath, Build.VERSION.SDK_INT, false, libsPath, null, null,
                              cacheKey, null /* classLoaderName */, null /* sharedLibraries */);
    }

    /**
     * Adds a new path the classpath of the given loader.
     * @throws IllegalStateException if the provided class loader is not a {@link PathClassLoader}.
     */
    void addPath(ClassLoader classLoader, String dexPath) {
        if (!(classLoader instanceof PathClassLoader)) {
            throw new IllegalStateException("class loader is not a PathClassLoader");
        }
        final PathClassLoader baseDexClassLoader = (PathClassLoader) classLoader;
        baseDexClassLoader.addDexPath(dexPath);
    }

    /**
     * @hide
     */
    void addNative(ClassLoader classLoader, Collection<String> libPaths) {
        if (!(classLoader instanceof PathClassLoader)) {
            throw new IllegalStateException("class loader is not a PathClassLoader");
        }
        final PathClassLoader baseDexClassLoader = (PathClassLoader) classLoader;
        baseDexClassLoader.addNativePath(libPaths);
    }

    @UnsupportedAppUsage
    private final ArrayMap<String, ClassLoader> mLoaders = new ArrayMap<>();

    private static final ApplicationLoaders gApplicationLoaders = new ApplicationLoaders();

    private static class CachedClassLoader {
        ClassLoader loader;

        /**
         * The shared libraries used when constructing loader for verification.
         */
        List<ClassLoader> sharedLibraries;
    }

    /**
     * This is a map of zip to associated class loader.
     */
    private Map<String, CachedClassLoader> mSystemLibsCacheMap = null;
}
