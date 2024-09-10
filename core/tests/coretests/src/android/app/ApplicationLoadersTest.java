/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.pm.SharedLibraryInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.google.android.collect.Lists;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class ApplicationLoadersTest {

    // a library installed onto the device with no dependencies
    private static final String LIB_A = "/system/framework/android.hidl.base-V1.0-java.jar";
    // a library installed onto the device which only depends on A
    private static final String LIB_DEP_A = "/system/framework/android.hidl.manager-V1.0-java.jar";
    // a commonly used, non-BCP, app-facing library installed onto the device
    private static final String LIB_APACHE_HTTP = "/system/framework/org.apache.http.legacy.jar";

    private static SharedLibraryInfo createLib(String zip) {
        return new SharedLibraryInfo(
                zip, null /*packageName*/, null /*codePaths*/, null /*name*/, 0 /*version*/,
                SharedLibraryInfo.TYPE_BUILTIN, null /*declaringPackage*/,
                null /*dependentPackages*/, null /*dependencies*/, false /*isNative*/);
    }

    @Test
    public void testGetNonExistantLib() {
        ApplicationLoaders loaders = new ApplicationLoaders();
        assertNull(loaders.getCachedNonBootclasspathSystemLib(
                "/system/framework/nonexistantlib.jar", null, null, null));
    }

    @Test
    public void testCacheExistantLib() {
        ApplicationLoaders loaders = new ApplicationLoaders();
        SharedLibraryInfo libA = createLib(LIB_A);

        loaders.createAndCacheNonBootclasspathSystemClassLoaders(Lists.newArrayList(libA));

        assertNotNull(loaders.getCachedNonBootclasspathSystemLib(
                LIB_A, null, null, null));
    }

    @Test
    public void testNonNullParent() {
        ApplicationLoaders loaders = new ApplicationLoaders();
        SharedLibraryInfo libA = createLib(LIB_A);

        ClassLoader parent = ClassLoader.getSystemClassLoader();
        assertNotEquals(null, parent);

        loaders.createAndCacheNonBootclasspathSystemClassLoaders(Lists.newArrayList(libA));

        assertNull(loaders.getCachedNonBootclasspathSystemLib(
                LIB_A, parent, null, null));
    }

    @Test
    public void testNonNullClassLoaderNamespace() {
        ApplicationLoaders loaders = new ApplicationLoaders();
        SharedLibraryInfo libA = createLib(LIB_A);

        loaders.createAndCacheNonBootclasspathSystemClassLoaders(Lists.newArrayList(libA));

        assertNull(loaders.getCachedNonBootclasspathSystemLib(
                LIB_A, null, "other classloader", null));
    }

    @Test
    public void testDifferentSharedLibraries() {
        ApplicationLoaders loaders = new ApplicationLoaders();
        SharedLibraryInfo libA = createLib(LIB_A);

        // any other existant lib
        ClassLoader dep = ClassLoader.getSystemClassLoader();
        ArrayList<ClassLoader> sharedLibraries = new ArrayList<>();
        sharedLibraries.add(dep);

        loaders.createAndCacheNonBootclasspathSystemClassLoaders(Lists.newArrayList(libA));

        assertNull(loaders.getCachedNonBootclasspathSystemLib(
                LIB_A, null, null, sharedLibraries));
    }

    @Test
    public void testDependentLibs() {
        ApplicationLoaders loaders = new ApplicationLoaders();
        SharedLibraryInfo libA = createLib(LIB_A);
        SharedLibraryInfo libB = createLib(LIB_DEP_A);
        libB.addDependency(libA);

        loaders.createAndCacheNonBootclasspathSystemClassLoaders(
                Lists.newArrayList(libA, libB));

        ClassLoader loadA = loaders.getCachedNonBootclasspathSystemLib(
                LIB_A, null, null, null);
        assertNotEquals(null, loadA);

        ArrayList<ClassLoader> sharedLibraries = new ArrayList<>();
        sharedLibraries.add(loadA);

        assertNotNull(loaders.getCachedNonBootclasspathSystemLib(
                LIB_DEP_A, null, null, sharedLibraries));
    }

    @Test(expected = IllegalStateException.class)
    public void testDependentLibsWrongOrder() {
        ApplicationLoaders loaders = new ApplicationLoaders();
        SharedLibraryInfo libA = createLib(LIB_A);
        SharedLibraryInfo libB = createLib(LIB_DEP_A);
        libB.addDependency(libA);

        loaders.createAndCacheNonBootclasspathSystemClassLoaders(Lists.newArrayList(libB, libA));
    }

    @Test
    public void testCacheApacheHttpLegacy() {
        ApplicationLoaders loaders = new ApplicationLoaders();
        SharedLibraryInfo libApacheHttp = createLib(LIB_APACHE_HTTP);

        loaders.createAndCacheNonBootclasspathSystemClassLoaders(Lists.newArrayList(libApacheHttp));

        assertNotNull(loaders.getCachedNonBootclasspathSystemLib(
                LIB_APACHE_HTTP, null, null, null));
    }
}
