/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.pm.dex;

import android.content.pm.ApplicationInfo;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.os.ClassLoaderFactory;
import com.android.server.pm.PackageDexOptimizer;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class DexoptUtils {
    private static final String TAG = "DexoptUtils";

    private DexoptUtils() {}

    /**
     * Creates the class loader context dependencies for each of the application code paths.
     * The returned array contains the class loader contexts that needs to be passed to dexopt in
     * order to ensure correct optimizations. "Code" paths with no actual code, as specified by
     * {@param pathsWithCode}, are ignored and will have null as their context in the returned array
     * (configuration splits are an example of paths without code).
     *
     * A class loader context describes how the class loader chain should be built by dex2oat
     * in order to ensure that classes are resolved during compilation as they would be resolved
     * at runtime. The context will be encoded in the compiled code. If at runtime the dex file is
     * loaded in a different context (with a different set of class loaders or a different
     * classpath), the compiled code will be rejected.
     *
     * Note that the class loader context only includes dependencies and not the code path itself.
     * The contexts are created based on the application split dependency list and
     * the provided shared libraries.
     *
     * All the code paths encoded in the context will be relative to the base directory. This
     * enables stage compilation where compiler artifacts may be moved around.
     *
     * The result is indexed as follows:
     *   - index 0 contains the context for the base apk
     *   - index 1 to n contain the context for the splits in the order determined by
     *     {@code info.getSplitCodePaths()}
     *
     * IMPORTANT: keep this logic in sync with the loading code in {@link android.app.LoadedApk}
     * and pay attention to the way the classpath is created for the non isolated mode in:
     * {@link android.app.LoadedApk#makePaths(
     * android.app.ActivityThread, boolean, ApplicationInfo, List, List)}.
     */
    public static String[] getClassLoaderContexts(ApplicationInfo info,
            String[] sharedLibraries, boolean[] pathsWithCode) {
        // The base class loader context contains only the shared library.
        String sharedLibrariesClassPath = encodeClasspath(sharedLibraries);
        String baseApkContextClassLoader = encodeClassLoader(
                sharedLibrariesClassPath, info.classLoaderName);

        if (info.getSplitCodePaths() == null) {
            // The application has no splits.
            return new String[] {baseApkContextClassLoader};
        }

        // The application has splits. Compute their class loader contexts.

        // First, cache the relative paths of the splits and do some sanity checks
        String[] splitRelativeCodePaths = getSplitRelativeCodePaths(info);

        // The splits have an implicit dependency on the base apk.
        // This means that we have to add the base apk file in addition to the shared libraries.
        String baseApkName = new File(info.getBaseCodePath()).getName();
        String sharedLibrariesAndBaseClassPath =
                encodeClasspath(sharedLibrariesClassPath, baseApkName);

        // The result is stored in classLoaderContexts.
        // Index 0 is the class loaded context for the base apk.
        // Index `i` is the class loader context encoding for split `i`.
        String[] classLoaderContexts = new String[/*base apk*/ 1 + splitRelativeCodePaths.length];
        classLoaderContexts[0] = pathsWithCode[0] ? baseApkContextClassLoader : null;

        if (!info.requestsIsolatedSplitLoading() || info.splitDependencies == null) {
            // If the app didn't request for the splits to be loaded in isolation or if it does not
            // declare inter-split dependencies, then all the splits will be loaded in the base
            // apk class loader (in the order of their definition).
            String classpath = sharedLibrariesAndBaseClassPath;
            for (int i = 1; i < classLoaderContexts.length; i++) {
                classLoaderContexts[i] = pathsWithCode[i]
                        ? encodeClassLoader(classpath, info.classLoaderName) : null;
                // Note that the splits with no code are not removed from the classpath computation.
                // i.e. split_n might get the split_n-1 in its classpath dependency even
                // if split_n-1 has no code.
                // The splits with no code do not matter for the runtime which ignores
                // apks without code when doing the classpath checks. As such we could actually
                // filter them but we don't do it in order to keep consistency with how the apps
                // are loaded.
                classpath = encodeClasspath(classpath, splitRelativeCodePaths[i - 1]);
            }
        } else {
            // In case of inter-split dependencies, we need to walk the dependency chain of each
            // split. We do this recursively and store intermediate results in classLoaderContexts.

            // First, look at the split class loaders and cache their individual contexts (i.e.
            // the class loader + the name of the split). This is an optimization to avoid
            // re-computing them during the recursive call.
            // The cache is stored in splitClassLoaderEncodingCache. The difference between this and
            // classLoaderContexts is that the later contains the full chain of class loaders for
            // a given split while splitClassLoaderEncodingCache only contains a single class loader
            // encoding.
            String[] splitClassLoaderEncodingCache = new String[splitRelativeCodePaths.length];
            for (int i = 0; i < splitRelativeCodePaths.length; i++) {
                splitClassLoaderEncodingCache[i] = encodeClassLoader(splitRelativeCodePaths[i],
                        info.splitClassLoaderNames[i]);
            }
            String splitDependencyOnBase = encodeClassLoader(
                    sharedLibrariesAndBaseClassPath, info.classLoaderName);
            SparseArray<int[]> splitDependencies = info.splitDependencies;

            // Note that not all splits have dependencies (e.g. configuration splits)
            // The splits without dependencies will have classLoaderContexts[config_split_index]
            // set to null after this step.
            for (int i = 1; i < splitDependencies.size(); i++) {
                int splitIndex = splitDependencies.keyAt(i);
                if (pathsWithCode[splitIndex]) {
                    // Compute the class loader context only for the splits with code.
                    getParentDependencies(splitIndex, splitClassLoaderEncodingCache,
                            splitDependencies, classLoaderContexts, splitDependencyOnBase);
                }
            }

            // At this point classLoaderContexts contains only the parent dependencies.
            // We also need to add the class loader of the current split which should
            // come first in the context.
            for (int i = 1; i < classLoaderContexts.length; i++) {
                String splitClassLoader = encodeClassLoader("", info.splitClassLoaderNames[i - 1]);
                if (pathsWithCode[i]) {
                    // If classLoaderContexts[i] is null it means that the split does not have
                    // any dependency. In this case its context equals its declared class loader.
                    classLoaderContexts[i] = classLoaderContexts[i] == null
                            ? splitClassLoader
                            : encodeClassLoaderChain(splitClassLoader, classLoaderContexts[i]);
                } else {
                    // This is a split without code, it has no dependency and it is not compiled.
                    // Its context will be null.
                    classLoaderContexts[i] = null;
                }
            }
        }

        return classLoaderContexts;
    }

    /**
     * Recursive method to generate the class loader context dependencies for the split with the
     * given index. {@param classLoaderContexts} acts as an accumulator. Upton return
     * {@code classLoaderContexts[index]} will contain the split dependency.
     * During computation, the method may resolve the dependencies of other splits as it traverses
     * the entire parent chain. The result will also be stored in {@param classLoaderContexts}.
     *
     * Note that {@code index 0} denotes the base apk and it is special handled. When the
     * recursive call hits {@code index 0} the method returns {@code splitDependencyOnBase}.
     * {@code classLoaderContexts[0]} is not modified in this method.
     *
     * @param index the index of the split (Note that index 0 denotes the base apk)
     * @param splitClassLoaderEncodingCache the class loader encoding for the individual splits.
     *    It contains only the split class loader and not the the base. The split
     *    with {@code index} has its context at {@code splitClassLoaderEncodingCache[index - 1]}.
     * @param splitDependencies the dependencies for all splits. Note that in this array index 0
     *    is the base and splits start from index 1.
     * @param classLoaderContexts the result accumulator. index 0 is the base and never set. Splits
     *    start at index 1.
     * @param splitDependencyOnBase the encoding of the implicit split dependency on base.
     */
    private static String getParentDependencies(int index, String[] splitClassLoaderEncodingCache,
            SparseArray<int[]> splitDependencies, String[] classLoaderContexts,
            String splitDependencyOnBase) {
        // If we hit the base apk return its custom dependency list which is
        // sharedLibraries + base.apk
        if (index == 0) {
            return splitDependencyOnBase;
        }
        // Return the result if we've computed the splitDependencies for this index already.
        if (classLoaderContexts[index] != null) {
            return classLoaderContexts[index];
        }
        // Get the splitDependencies for the parent of this index and append its path to it.
        int parent = splitDependencies.get(index)[0];
        String parentDependencies = getParentDependencies(parent, splitClassLoaderEncodingCache,
                splitDependencies, classLoaderContexts, splitDependencyOnBase);

        // The split context is: `parent context + parent dependencies context`.
        String splitContext = (parent == 0) ?
                parentDependencies :
                encodeClassLoaderChain(splitClassLoaderEncodingCache[parent - 1], parentDependencies);
        classLoaderContexts[index] = splitContext;
        return splitContext;
    }

    /**
     * Encodes the shared libraries classpathElements in a format accepted by dexopt.
     * NOTE: Keep this in sync with the dexopt expectations! Right now that is
     * a list separated by ':'.
     */
    private static String encodeClasspath(String[] classpathElements) {
        if (classpathElements == null || classpathElements.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String element : classpathElements) {
            if (sb.length() != 0) {
                sb.append(":");
            }
            sb.append(element);
        }
        return sb.toString();
    }

    /**
     * Adds an element to the encoding of an existing classpath.
     * {@see PackageDexOptimizer.encodeClasspath(String[])}
     */
    private static String encodeClasspath(String classpath, String newElement) {
        return classpath.isEmpty() ? newElement : (classpath + ":" + newElement);
    }

    /**
     * Encodes a single class loader dependency starting from {@param path} and
     * {@param classLoaderName}.
     * When classpath is {@link PackageDexOptimizer#SKIP_SHARED_LIBRARY_CHECK}, the method returns
     * the same. This special property is used only during OTA.
     * NOTE: Keep this in sync with the dexopt expectations! Right now that is either "PCL[path]"
     * for a PathClassLoader or "DLC[path]" for a DelegateLastClassLoader.
     */
    /*package*/ static String encodeClassLoader(String classpath, String classLoaderName) {
        if (classpath.equals(PackageDexOptimizer.SKIP_SHARED_LIBRARY_CHECK)) {
            return classpath;
        }
        String classLoaderDexoptEncoding = classLoaderName;
        if (ClassLoaderFactory.isPathClassLoaderName(classLoaderName)) {
            classLoaderDexoptEncoding = "PCL";
        } else if (ClassLoaderFactory.isDelegateLastClassLoaderName(classLoaderName)) {
            classLoaderDexoptEncoding = "DLC";
        } else {
            Slog.wtf(TAG, "Unsupported classLoaderName: " + classLoaderName);
        }
        return classLoaderDexoptEncoding + "[" + classpath + "]";
    }

    /**
     * Links to dependencies together in a format accepted by dexopt.
     * For the special case when either of cl1 or cl2 equals
     * {@link PackageDexOptimizer#SKIP_SHARED_LIBRARY_CHECK}, the method returns the same. This
     * property is used only during OTA.
     * NOTE: Keep this in sync with the dexopt expectations! Right now that is a list of split
     * dependencies {@see encodeClassLoader} separated by ';'.
     */
    /*package*/ static String encodeClassLoaderChain(String cl1, String cl2) {
        if (cl1.equals(PackageDexOptimizer.SKIP_SHARED_LIBRARY_CHECK) ||
                cl2.equals(PackageDexOptimizer.SKIP_SHARED_LIBRARY_CHECK)) {
            return PackageDexOptimizer.SKIP_SHARED_LIBRARY_CHECK;
        }
        if (cl1.isEmpty()) return cl2;
        if (cl2.isEmpty()) return cl1;
        return cl1 + ";" + cl2;
    }

    /**
     * Compute the class loader context for the dex files present in the classpath of the first
     * class loader from the given list (referred in the code as the {@code loadingClassLoader}).
     * Each dex files gets its own class loader context in the returned array.
     *
     * Example:
     *    If classLoadersNames = {"dalvik.system.DelegateLastClassLoader",
     *    "dalvik.system.PathClassLoader"} and classPaths = {"foo.dex:bar.dex", "other.dex"}
     *    The output will be
     *    {"DLC[];PCL[other.dex]", "DLC[foo.dex];PCL[other.dex]"}
     *    with "DLC[];PCL[other.dex]" being the context for "foo.dex"
     *    and "DLC[foo.dex];PCL[other.dex]" the context for "bar.dex".
     *
     * If any of the class loaders names is unsupported the method will return null.
     *
     * The argument lists must be non empty and of the same size.
     *
     * @param classLoadersNames the names of the class loaders present in the loading chain. The
     *    list encodes the class loader chain in the natural order. The first class loader has
     *    the second one as its parent and so on.
     * @param classPaths the class paths for the elements of {@param classLoadersNames}. The
     *     the first element corresponds to the first class loader and so on. A classpath is
     *     represented as a list of dex files separated by {@code File.pathSeparator}.
     *     The return context will be for the dex files found in the first class path.
     */
    /*package*/ static String[] processContextForDexLoad(List<String> classLoadersNames,
            List<String> classPaths) {
        if (classLoadersNames.size() != classPaths.size()) {
            throw new IllegalArgumentException(
                    "The size of the class loader names and the dex paths do not match.");
        }
        if (classLoadersNames.isEmpty()) {
            throw new IllegalArgumentException("Empty classLoadersNames");
        }

        // Compute the context for the parent class loaders.
        String parentContext = "";
        // We know that these lists are actually ArrayLists so getting the elements by index
        // is fine (they come over binder). Even if something changes we expect the sizes to be
        // very small and it shouldn't matter much.
        for (int i = 1; i < classLoadersNames.size(); i++) {
            if (!ClassLoaderFactory.isValidClassLoaderName(classLoadersNames.get(i))) {
                return null;
            }
            String classpath = encodeClasspath(classPaths.get(i).split(File.pathSeparator));
            parentContext = encodeClassLoaderChain(parentContext,
                    encodeClassLoader(classpath, classLoadersNames.get(i)));
        }

        // Now compute the class loader context for each dex file from the first classpath.
        String loadingClassLoader = classLoadersNames.get(0);
        if (!ClassLoaderFactory.isValidClassLoaderName(loadingClassLoader)) {
            return null;
        }
        String[] loadedDexPaths = classPaths.get(0).split(File.pathSeparator);
        String[] loadedDexPathsContext = new String[loadedDexPaths.length];
        String currentLoadedDexPathClasspath = "";
        for (int i = 0; i < loadedDexPaths.length; i++) {
            String dexPath = loadedDexPaths[i];
            String currentContext = encodeClassLoader(
                    currentLoadedDexPathClasspath, loadingClassLoader);
            loadedDexPathsContext[i] = encodeClassLoaderChain(currentContext, parentContext);
            currentLoadedDexPathClasspath = encodeClasspath(currentLoadedDexPathClasspath, dexPath);
        }
        return loadedDexPathsContext;
    }

    /**
     * Returns the relative paths of the splits declared by the application {@code info}.
     * Assumes that the application declares a non-null array of splits.
     */
    private static String[] getSplitRelativeCodePaths(ApplicationInfo info) {
        String baseCodePath = new File(info.getBaseCodePath()).getParent();
        String[] splitCodePaths = info.getSplitCodePaths();
        String[] splitRelativeCodePaths = new String[splitCodePaths.length];
        for (int i = 0; i < splitCodePaths.length; i++) {
            File pathFile = new File(splitCodePaths[i]);
            splitRelativeCodePaths[i] = pathFile.getName();
            // Sanity check that the base paths of the splits are all the same.
            String basePath = pathFile.getParent();
            if (!basePath.equals(baseCodePath)) {
                Slog.wtf(TAG, "Split paths have different base paths: " + basePath + " and " +
                        baseCodePath);
            }
        }
        return splitRelativeCodePaths;
    }
}
