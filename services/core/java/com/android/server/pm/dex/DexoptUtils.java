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

import java.io.File;

public final class DexoptUtils {
    private static final String TAG = "DexoptUtils";

    private DexoptUtils() {}

    /**
     * Creates the class loader context dependencies for each of the application code paths.
     * The returned array contains the class loader contexts that needs to be passed to dexopt in
     * order to ensure correct optimizations.
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
     */
    public static String[] getClassLoaderContexts(ApplicationInfo info, String[] sharedLibraries) {
        // The base class loader context contains only the shared library.
        String sharedLibrariesClassPath = encodeClasspath(sharedLibraries);
        String baseApkContextClassLoader = encodeClassLoader(
                sharedLibrariesClassPath, info.classLoaderName);

        String[] splitCodePaths = info.getSplitCodePaths();

        if (splitCodePaths == null) {
            // The application has no splits.
            return new String[] {baseApkContextClassLoader};
        }

        // The application has splits. Compute their class loader contexts.

        // The splits have an implicit dependency on the base apk.
        // This means that we have to add the base apk file in addition to the shared libraries.
        String baseApkName = new File(info.getBaseCodePath()).getName();
        String splitDependencyOnBase = encodeClassLoader(
                encodeClasspath(sharedLibrariesClassPath, baseApkName),
                info.classLoaderName);

        // The result is stored in classLoaderContexts.
        // Index 0 is the class loaded context for the base apk.
        // Index `i` is the class loader context encoding for split `i`.
        String[] classLoaderContexts = new String[/*base apk*/ 1 + splitCodePaths.length];
        classLoaderContexts[0] = baseApkContextClassLoader;

        SparseArray<int[]> splitDependencies = info.splitDependencies;

        if (splitDependencies == null) {
            // If there are no inter-split dependencies, populate the result with the implicit
            // dependency on the base apk.
            for (int i = 1; i < classLoaderContexts.length; i++) {
                classLoaderContexts[i] = splitDependencyOnBase;
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
            String baseCodePath = new File(info.getBaseCodePath()).getParent();
            String[] splitClassLoaderEncodingCache = new String[splitCodePaths.length];
            for (int i = 0; i < splitCodePaths.length; i++) {
                File pathFile = new File(splitCodePaths[i]);
                String fileName = pathFile.getName();
                splitClassLoaderEncodingCache[i] = encodeClassLoader(fileName,
                        info.splitClassLoaderNames[i]);
                // Sanity check that the base paths of the splits are all the same.
                String basePath = pathFile.getParent();
                if (!basePath.equals(baseCodePath)) {
                    Slog.wtf(TAG, "Split paths have different base paths: " + basePath + " and " +
                            baseCodePath);
                }
            }
            for (int i = 1; i < splitDependencies.size(); i++) {
                getParentDependencies(splitDependencies.keyAt(i), splitClassLoaderEncodingCache,
                        splitDependencies, classLoaderContexts, splitDependencyOnBase);
            }
        }

        // At this point classLoaderContexts contains only the parent dependencies.
        // We also need to add the class loader of the current split which should
        // come first in the context.
        for (int i = 1; i < classLoaderContexts.length; i++) {
            String splitClassLoader = encodeClassLoader("", info.splitClassLoaderNames[i - 1]);
            classLoaderContexts[i] = encodeClassLoaderChain(
                    splitClassLoader, classLoaderContexts[i]);
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
     * NOTE: Keep this in sync with the dexopt expectations! Right now that is either "PCL[path]"
     * for a PathClassLoader or "DLC[path]" for a DelegateLastClassLoader.
     */
    private static String encodeClassLoader(String classpath, String classLoaderName) {
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
     * NOTE: Keep this in sync with the dexopt expectations! Right now that is a list of split
     * dependencies {@see encodeClassLoader} separated by ';'.
     */
    private static String encodeClassLoaderChain(String cl1, String cl2) {
        return cl1.isEmpty() ? cl2 : (cl1 + ";" + cl2);
    }
}
