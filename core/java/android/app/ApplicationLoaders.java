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

import android.os.Build;
import android.os.Trace;
import android.util.ArrayMap;
import com.android.internal.os.ClassLoaderFactory;
import dalvik.system.PathClassLoader;

/** @hide */
public class ApplicationLoaders {
    public static ApplicationLoaders getDefault() {
        return gApplicationLoaders;
    }

    ClassLoader getClassLoader(String zip, int targetSdkVersion, boolean isBundled,
                               String librarySearchPath, String libraryPermittedPath,
                               ClassLoader parent, String classLoaderName) {
        // For normal usage the cache key used is the same as the zip path.
        return getClassLoader(zip, targetSdkVersion, isBundled, librarySearchPath,
                              libraryPermittedPath, parent, zip, classLoaderName);
    }

    private ClassLoader getClassLoader(String zip, int targetSdkVersion, boolean isBundled,
                                       String librarySearchPath, String libraryPermittedPath,
                                       ClassLoader parent, String cacheKey,
                                       String classLoaderName) {
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
                        targetSdkVersion, isBundled, classLoaderName);

                Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);

                Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "setupVulkanLayerPath");
                setupVulkanLayerPath(classloader, librarySearchPath);
                Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);

                mLoaders.put(cacheKey, classloader);
                return classloader;
            }

            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, zip);
            ClassLoader loader = ClassLoaderFactory.createClassLoader(
                    zip, null, parent, classLoaderName);
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
            return loader;
        }
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
                              cacheKey, null /* classLoaderName */);
    }

    private static native void setupVulkanLayerPath(ClassLoader classLoader, String librarySearchPath);

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

    private final ArrayMap<String, ClassLoader> mLoaders = new ArrayMap<>();

    private static final ApplicationLoaders gApplicationLoaders = new ApplicationLoaders();
}
