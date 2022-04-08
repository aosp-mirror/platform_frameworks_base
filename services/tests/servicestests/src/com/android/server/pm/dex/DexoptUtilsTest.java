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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.pm.SharedLibraryInfo;
import android.content.pm.parsing.ParsingPackage;
import android.util.SparseArray;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.PackageImpl;
import com.android.server.pm.parsing.pkg.ParsedPackage;

import dalvik.system.DelegateLastClassLoader;
import dalvik.system.DexClassLoader;
import dalvik.system.PathClassLoader;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class DexoptUtilsTest {
    private static final String DEX_CLASS_LOADER_NAME = DexClassLoader.class.getName();
    private static final String PATH_CLASS_LOADER_NAME = PathClassLoader.class.getName();
    private static final String DELEGATE_LAST_CLASS_LOADER_NAME =
            DelegateLastClassLoader.class.getName();

    private static class TestData {
        AndroidPackage pkg;
        boolean[] pathsWithCode;
    }

    private TestData createMockApplicationInfo(String baseClassLoader, boolean addSplits,
            boolean addSplitDependencies, boolean isolatedSplitLoading) {
        String codeDir = "/data/app/mock.android.com";
        ParsingPackage parsingPackage = PackageImpl.forTesting("mock.android.com",
                codeDir + "/base.dex")
                .setClassLoaderName(baseClassLoader);

        parsingPackage.setIsolatedSplitLoading(isolatedSplitLoading);

        boolean[] pathsWithCode;
        if (!addSplits) {
            pathsWithCode = new boolean[] {true};
        } else {
            pathsWithCode = new boolean[9];
            Arrays.fill(pathsWithCode, true);
            pathsWithCode[7] = false;  // config split

            String[] splitCodePaths = new String[]{
                    codeDir + "/base-1.dex",
                    codeDir + "/base-2.dex",
                    codeDir + "/base-3.dex",
                    codeDir + "/base-4.dex",
                    codeDir + "/base-5.dex",
                    codeDir + "/base-6.dex",
                    codeDir + "/config-split-7.dex",
                    codeDir + "/feature-no-deps.dex"
            };

            String[] splitNames = new String[splitCodePaths.length];
            int[] splitRevisionCodes = new int[splitCodePaths.length];
            SparseArray<int[]> splitDependencies = null;

            if (addSplitDependencies) {
                splitDependencies = new SparseArray<>(splitCodePaths.length);
                splitDependencies.put(0, new int[] {-1}); // base has no dependency
                splitDependencies.put(1, new int[] {2}); // split 1 depends on 2
                splitDependencies.put(2, new int[] {4}); // split 2 depends on 4
                splitDependencies.put(3, new int[] {4}); // split 3 depends on 4
                splitDependencies.put(4, new int[] {0}); // split 4 depends on base
                splitDependencies.put(5, new int[] {0}); // split 5 depends on base
                splitDependencies.put(6, new int[] {5}); // split 6 depends on 5
                // Do not add the config split to the dependency list.
                // Do not add the feature split with no dependency to the dependency list.
            }

            parsingPackage
                    .asSplit(
                            splitNames,
                            splitCodePaths,
                            splitRevisionCodes,
                            splitDependencies
                    )
                    .setSplitClassLoaderName(0, DELEGATE_LAST_CLASS_LOADER_NAME)
                    .setSplitClassLoaderName(1, DELEGATE_LAST_CLASS_LOADER_NAME)
                    .setSplitClassLoaderName(2, PATH_CLASS_LOADER_NAME)
                    .setSplitClassLoaderName(3, DEX_CLASS_LOADER_NAME)
                    .setSplitClassLoaderName(4, PATH_CLASS_LOADER_NAME)
                    // A null class loader name should default to PathClassLoader
                    .setSplitClassLoaderName(5, null)
                    // The config split gets a null class loader
                    .setSplitClassLoaderName(6, null)
                    // The feature split with no dependency and no specified class loader.
                    .setSplitClassLoaderName(7, null);
        }

        ParsedPackage parsedPackage = (ParsedPackage) parsingPackage.hideAsParsed();

        TestData data = new TestData();
        data.pkg = parsedPackage.hideAsFinal();
        data.pathsWithCode = pathsWithCode;
        return data;
    }

    private List<SharedLibraryInfo> createMockSharedLibrary(String [] sharedLibrary) {
        SharedLibraryInfo info = new SharedLibraryInfo(null, null, Arrays.asList(sharedLibrary),
                null, 0L, SharedLibraryInfo.TYPE_STATIC, null, null, null);
        ArrayList<SharedLibraryInfo> libraries = new ArrayList<>();
        libraries.add(info);
        return libraries;
    }

    @Test
    public void testSplitChain() {
        TestData data = createMockApplicationInfo(PATH_CLASS_LOADER_NAME, true, true, true);
        List<SharedLibraryInfo> sharedLibrary =
                createMockSharedLibrary(new String[] {"a.dex", "b.dex"});
        String[] contexts = DexoptUtils.getClassLoaderContexts(
                data.pkg, sharedLibrary, data.pathsWithCode);

        assertEquals(9, contexts.length);
        assertEquals("PCL[]{PCL[a.dex:b.dex]}", contexts[0]);
        assertEquals("DLC[];DLC[base-2.dex];PCL[base-4.dex];PCL[base.dex]{PCL[a.dex:b.dex]}",
                contexts[1]);
        assertEquals("DLC[];PCL[base-4.dex];PCL[base.dex]{PCL[a.dex:b.dex]}", contexts[2]);
        assertEquals("PCL[];PCL[base-4.dex];PCL[base.dex]{PCL[a.dex:b.dex]}", contexts[3]);
        assertEquals("PCL[];PCL[base.dex]{PCL[a.dex:b.dex]}", contexts[4]);
        assertEquals("PCL[];PCL[base.dex]{PCL[a.dex:b.dex]}", contexts[5]);
        assertEquals("PCL[];PCL[base-5.dex];PCL[base.dex]{PCL[a.dex:b.dex]}", contexts[6]);
        assertEquals(null, contexts[7]);  // config split
        assertEquals("PCL[]", contexts[8]);  // feature split with no dependency
    }

    @Test
    public void testSplitChainNoSplitDependencies() {
        TestData data = createMockApplicationInfo(PATH_CLASS_LOADER_NAME, true, false, true);
        List<SharedLibraryInfo> sharedLibrary =
                createMockSharedLibrary(new String[] {"a.dex", "b.dex"});
        String[] contexts = DexoptUtils.getClassLoaderContexts(
                data.pkg, sharedLibrary, data.pathsWithCode);

        assertEquals(9, contexts.length);
        assertEquals("PCL[]{PCL[a.dex:b.dex]}", contexts[0]);
        assertEquals("PCL[base.dex]{PCL[a.dex:b.dex]}", contexts[1]);
        assertEquals("PCL[base.dex:base-1.dex]{PCL[a.dex:b.dex]}", contexts[2]);
        assertEquals("PCL[base.dex:base-1.dex:base-2.dex]{PCL[a.dex:b.dex]}", contexts[3]);
        assertEquals(
                "PCL[base.dex:base-1.dex:base-2.dex:base-3.dex]{PCL[a.dex:b.dex]}",
                contexts[4]);
        assertEquals(
                "PCL[base.dex:base-1.dex:base-2.dex:base-3.dex:base-4.dex]{PCL[a.dex:b.dex]}",
                contexts[5]);
        assertEquals(
                "PCL[base.dex:base-1.dex:base-2.dex:base-3.dex:base-4.dex:base-5.dex]{PCL[a.dex:b.dex]}",
                contexts[6]);
        assertEquals(null, contexts[7]);  // config split
        assertEquals(
                "PCL[base.dex:base-1.dex:base-2.dex:base-3.dex:base-4.dex:base-5.dex:base-6.dex:config-split-7.dex]{PCL[a.dex:b.dex]}",
                contexts[8]);  // feature split with no dependency
    }

    @Test
    public void testSplitChainNoIsolationNoSharedLibrary() {
        TestData data = createMockApplicationInfo(PATH_CLASS_LOADER_NAME, true, true, false);
        String[] contexts = DexoptUtils.getClassLoaderContexts(
                data.pkg, null, data.pathsWithCode);

        assertEquals(9, contexts.length);
        assertEquals("PCL[]", contexts[0]);
        assertEquals("PCL[base.dex]", contexts[1]);
        assertEquals("PCL[base.dex:base-1.dex]", contexts[2]);
        assertEquals("PCL[base.dex:base-1.dex:base-2.dex]", contexts[3]);
        assertEquals("PCL[base.dex:base-1.dex:base-2.dex:base-3.dex]", contexts[4]);
        assertEquals("PCL[base.dex:base-1.dex:base-2.dex:base-3.dex:base-4.dex]", contexts[5]);
        assertEquals(
                "PCL[base.dex:base-1.dex:base-2.dex:base-3.dex:base-4.dex:base-5.dex]",
                contexts[6]);
        assertEquals(null, contexts[7]);  // config split
        assertEquals(
                "PCL[base.dex:base-1.dex:base-2.dex:base-3.dex:base-4.dex:base-5.dex:base-6.dex:config-split-7.dex]",
                contexts[8]);  // feature split with no dependency
    }

    @Test
    public void testSplitChainNoSharedLibraries() {
        TestData data = createMockApplicationInfo(
                DELEGATE_LAST_CLASS_LOADER_NAME, true, true, true);
        String[] contexts = DexoptUtils.getClassLoaderContexts(
                data.pkg, null, data.pathsWithCode);

        assertEquals(9, contexts.length);
        assertEquals("DLC[]", contexts[0]);
        assertEquals("DLC[];DLC[base-2.dex];PCL[base-4.dex];DLC[base.dex]", contexts[1]);
        assertEquals("DLC[];PCL[base-4.dex];DLC[base.dex]", contexts[2]);
        assertEquals("PCL[];PCL[base-4.dex];DLC[base.dex]", contexts[3]);
        assertEquals("PCL[];DLC[base.dex]", contexts[4]);
        assertEquals("PCL[];DLC[base.dex]", contexts[5]);
        assertEquals("PCL[];PCL[base-5.dex];DLC[base.dex]", contexts[6]);
        assertEquals(null, contexts[7]);  // config split
        assertEquals("PCL[]", contexts[8]);  // feature split with no dependency
    }

    @Test
    public void testSplitChainWithNullPrimaryClassLoader() {
        // A null classLoaderName should mean PathClassLoader.
        TestData data = createMockApplicationInfo(null, true, true, true);
        List<SharedLibraryInfo> sharedLibrary =
                createMockSharedLibrary(new String[] {"a.dex", "b.dex"});
        String[] contexts = DexoptUtils.getClassLoaderContexts(
                data.pkg, sharedLibrary, data.pathsWithCode);

        assertEquals(9, contexts.length);
        assertEquals("PCL[]{PCL[a.dex:b.dex]}", contexts[0]);
        assertEquals(
                "DLC[];DLC[base-2.dex];PCL[base-4.dex];PCL[base.dex]{PCL[a.dex:b.dex]}",
                contexts[1]);
        assertEquals("DLC[];PCL[base-4.dex];PCL[base.dex]{PCL[a.dex:b.dex]}", contexts[2]);
        assertEquals("PCL[];PCL[base-4.dex];PCL[base.dex]{PCL[a.dex:b.dex]}", contexts[3]);
        assertEquals("PCL[];PCL[base.dex]{PCL[a.dex:b.dex]}", contexts[4]);
        assertEquals("PCL[];PCL[base.dex]{PCL[a.dex:b.dex]}", contexts[5]);
        assertEquals("PCL[];PCL[base-5.dex];PCL[base.dex]{PCL[a.dex:b.dex]}", contexts[6]);
        assertEquals(null, contexts[7]);  // config split
        assertEquals("PCL[]", contexts[8]);  // feature split with no dependency
    }

    @Test
    public void tesNoSplits() {
        TestData data = createMockApplicationInfo(PATH_CLASS_LOADER_NAME, false, false, true);
        List<SharedLibraryInfo> sharedLibrary =
                createMockSharedLibrary(new String[] {"a.dex", "b.dex"});
        String[] contexts = DexoptUtils.getClassLoaderContexts(
                data.pkg, sharedLibrary, data.pathsWithCode);

        assertEquals(1, contexts.length);
        assertEquals("PCL[]{PCL[a.dex:b.dex]}", contexts[0]);
    }

    @Test
    public void tesNoSplitsNullClassLoaderName() {
        TestData data = createMockApplicationInfo(null, false, false, true);
        List<SharedLibraryInfo> sharedLibrary =
                createMockSharedLibrary(new String[] {"a.dex", "b.dex"});
        String[] contexts = DexoptUtils.getClassLoaderContexts(
                data.pkg, sharedLibrary, data.pathsWithCode);

        assertEquals(1, contexts.length);
        assertEquals("PCL[]{PCL[a.dex:b.dex]}", contexts[0]);
    }

    @Test
    public void tesNoSplitDelegateLast() {
        TestData data = createMockApplicationInfo(
                DELEGATE_LAST_CLASS_LOADER_NAME, false, false, true);
        List<SharedLibraryInfo> sharedLibrary =
                createMockSharedLibrary(new String[] {"a.dex", "b.dex"});
        String[] contexts = DexoptUtils.getClassLoaderContexts(
                data.pkg, sharedLibrary, data.pathsWithCode);

        assertEquals(1, contexts.length);
        assertEquals("DLC[]{PCL[a.dex:b.dex]}", contexts[0]);
    }

    @Test
    public void tesNoSplitsNoSharedLibraries() {
        TestData data = createMockApplicationInfo(PATH_CLASS_LOADER_NAME, false, false, true);
        String[] contexts = DexoptUtils.getClassLoaderContexts(
                data.pkg, null, data.pathsWithCode);

        assertEquals(1, contexts.length);
        assertEquals("PCL[]", contexts[0]);
    }

    @Test
    public void tesNoSplitDelegateLastNoSharedLibraries() {
        TestData data = createMockApplicationInfo(
                DELEGATE_LAST_CLASS_LOADER_NAME, false, false, true);
        String[] contexts = DexoptUtils.getClassLoaderContexts(
                data.pkg, null, data.pathsWithCode);

        assertEquals(1, contexts.length);
        assertEquals("DLC[]", contexts[0]);
    }

    @Test
    public void testContextWithNoCode() {
        TestData data = createMockApplicationInfo(null, true, false, true);
        Arrays.fill(data.pathsWithCode, false);

        List<SharedLibraryInfo> sharedLibrary =
                createMockSharedLibrary(new String[] {"a.dex", "b.dex"});
        String[] contexts = DexoptUtils.getClassLoaderContexts(
                data.pkg, sharedLibrary, data.pathsWithCode);

        assertEquals(9, contexts.length);
        assertEquals(null, contexts[0]);
        assertEquals(null, contexts[1]);
        assertEquals(null, contexts[2]);
        assertEquals(null, contexts[3]);
        assertEquals(null, contexts[4]);
        assertEquals(null, contexts[5]);
        assertEquals(null, contexts[6]);
        assertEquals(null, contexts[7]);
    }

    @Test
    public void testContextBaseNoCode() {
        TestData data = createMockApplicationInfo(null, true, true, true);
        data.pathsWithCode[0] = false;
        List<SharedLibraryInfo> sharedLibrary =
                createMockSharedLibrary(new String[] {"a.dex", "b.dex"});
        String[] contexts = DexoptUtils.getClassLoaderContexts(
                data.pkg, sharedLibrary, data.pathsWithCode);

        assertEquals(9, contexts.length);
        assertEquals(null, contexts[0]);
        assertEquals(
                "DLC[];DLC[base-2.dex];PCL[base-4.dex];PCL[base.dex]{PCL[a.dex:b.dex]}",
                contexts[1]);
        assertEquals("DLC[];PCL[base-4.dex];PCL[base.dex]{PCL[a.dex:b.dex]}", contexts[2]);
        assertEquals("PCL[];PCL[base-4.dex];PCL[base.dex]{PCL[a.dex:b.dex]}", contexts[3]);
        assertEquals("PCL[];PCL[base.dex]{PCL[a.dex:b.dex]}", contexts[4]);
        assertEquals("PCL[];PCL[base.dex]{PCL[a.dex:b.dex]}", contexts[5]);
        assertEquals("PCL[];PCL[base-5.dex];PCL[base.dex]{PCL[a.dex:b.dex]}", contexts[6]);
        assertEquals(null, contexts[7]);
    }

    @Test
    public void testSharedLibraryContext() {
        SharedLibraryInfo sharedLibrary =
                createMockSharedLibrary(new String[] {"a.dex", "b.dex"}).get(0);
        String context = DexoptUtils.getClassLoaderContext(sharedLibrary);
        assertEquals("PCL[]", context);

        SharedLibraryInfo otherSharedLibrary =
                createMockSharedLibrary(new String[] {"c.dex"}).get(0);
        otherSharedLibrary.addDependency(sharedLibrary);
        context = DexoptUtils.getClassLoaderContext(otherSharedLibrary);
        assertEquals("PCL[]{PCL[a.dex:b.dex]}", context);
    }

    @Test
    public void testProcessContextForDexLoad() {
        List<String> classLoaders = Arrays.asList(
                DELEGATE_LAST_CLASS_LOADER_NAME,
                PATH_CLASS_LOADER_NAME,
                PATH_CLASS_LOADER_NAME);
        List<String> classPaths = Arrays.asList(
                String.join(File.pathSeparator, "foo.dex", "bar.dex"),
                String.join(File.pathSeparator, "parent1.dex"),
                String.join(File.pathSeparator, "parent2.dex", "parent3.dex"));
        String[] context = DexoptUtils.processContextForDexLoad(classLoaders, classPaths);
        assertNotNull(context);
        assertEquals(2, context.length);
        assertEquals("DLC[];PCL[parent1.dex];PCL[parent2.dex:parent3.dex]", context[0]);
        assertEquals("DLC[foo.dex];PCL[parent1.dex];PCL[parent2.dex:parent3.dex]", context[1]);
    }

    @Test
    public void testProcessContextForDexLoadSingleElement() {
        List<String> classLoaders = Arrays.asList(PATH_CLASS_LOADER_NAME);
        List<String> classPaths = Arrays.asList(
                String.join(File.pathSeparator, "foo.dex", "bar.dex", "zoo.dex"));
        String[] context = DexoptUtils.processContextForDexLoad(classLoaders, classPaths);
        assertNotNull(context);
        assertEquals(3, context.length);
        assertEquals("PCL[]", context[0]);
        assertEquals("PCL[foo.dex]", context[1]);
        assertEquals("PCL[foo.dex:bar.dex]", context[2]);
    }

    @Test
    public void testProcessContextForDexLoadUnsupported() {
        List<String> classLoaders = Arrays.asList(
                DELEGATE_LAST_CLASS_LOADER_NAME,
                "unsupported.class.loader");
        List<String> classPaths = Arrays.asList(
                String.join(File.pathSeparator, "foo.dex", "bar.dex"),
                String.join(File.pathSeparator, "parent1.dex"));
        String[] context = DexoptUtils.processContextForDexLoad(classLoaders, classPaths);
        assertNull(context);
    }

    @Test
    public void testProcessContextForDexLoadNoClassPath() {
        List<String> classLoaders = Arrays.asList(
                DELEGATE_LAST_CLASS_LOADER_NAME,
                PATH_CLASS_LOADER_NAME);
        List<String> classPaths = Arrays.asList(
                String.join(File.pathSeparator, "foo.dex", "bar.dex"),
                null);
        String[] context = DexoptUtils.processContextForDexLoad(classLoaders, classPaths);
        assertNull(context);
    }

    @Test
    public void testProcessContextForDexLoadIllegalCallEmptyList() {
        boolean gotException = false;
        try {
            DexoptUtils.processContextForDexLoad(Collections.emptyList(), Collections.emptyList());
        } catch (IllegalArgumentException ignore) {
            gotException = true;
        }
        assertTrue(gotException);
    }

    @Test
    public void testProcessContextForDexLoadIllegalCallDifferentSize() {
        boolean gotException = false;
        try {
            DexoptUtils.processContextForDexLoad(Collections.emptyList(), Arrays.asList("a"));
        } catch (IllegalArgumentException ignore) {
            gotException = true;
        }
        assertTrue(gotException);
    }

    @Test
    public void testEncodeClassLoader() {
        assertEquals("PCL[xyz]", DexoptUtils.encodeClassLoader("xyz",
                "dalvik.system.PathClassLoader"));
        assertEquals("PCL[xyz]", DexoptUtils.encodeClassLoader("xyz",
                "dalvik.system.DexClassLoader"));
        assertEquals("DLC[xyz]", DexoptUtils.encodeClassLoader("xyz",
                "dalvik.system.DelegateLastClassLoader"));
        assertEquals("PCL[xyz]", DexoptUtils.encodeClassLoader("xyz", null));
        assertEquals("abc[xyz]", DexoptUtils.encodeClassLoader("xyz", "abc"));

        try {
            DexoptUtils.encodeClassLoader(null, "abc");
            fail(); // Exception should be caught.
        } catch (NullPointerException expected) {}
    }

    @Test
    public void testEncodeClassLoaderChain() {
        assertEquals("PCL[a];DLC[b]", DexoptUtils.encodeClassLoaderChain("PCL[a]",
                "DLC[b]"));
        try {
            DexoptUtils.encodeClassLoaderChain("a", null);
            fail(); // exception is expected
        } catch (NullPointerException expected) {}

        try {
            DexoptUtils.encodeClassLoaderChain(null, "b");
            fail(); // exception is expected
        } catch (NullPointerException expected) {}
    }
}
