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
 * limitations under the License.
 */

package android.content.pm;

import android.content.pm.PackageParser.Package;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Modifies {@link Package} in order to maintain backwards compatibility.
 *
 * @hide
 */
@VisibleForTesting
public class PackageBackwardCompatibility extends PackageSharedLibraryUpdater {

    private static final String TAG = PackageBackwardCompatibility.class.getSimpleName();

    private static final String ANDROID_TEST_MOCK = "android.test.mock";

    private static final String ANDROID_TEST_RUNNER = "android.test.runner";

    private static final PackageBackwardCompatibility INSTANCE;

    static {
        String className = "android.content.pm.OrgApacheHttpLegacyUpdater";
        Class<? extends PackageSharedLibraryUpdater> clazz;
        try {
            clazz = (PackageBackwardCompatibility.class.getClassLoader()
                    .loadClass(className)
                    .asSubclass(PackageSharedLibraryUpdater.class));
        } catch (ClassNotFoundException e) {
            Log.i(TAG, "Could not find " + className + ", ignoring");
            clazz = null;
        }

        boolean hasOrgApacheHttpLegacy = false;
        final List<PackageSharedLibraryUpdater> packageUpdaters = new ArrayList<>();
        if (clazz == null) {
            // Add an updater that will remove any references to org.apache.http.library from the
            // package so that it does not try and load the library when it is on the
            // bootclasspath.
            packageUpdaters.add(new RemoveUnnecessaryOrgApacheHttpLegacyLibrary());
        } else {
            try {
                packageUpdaters.add(clazz.getConstructor().newInstance());
                hasOrgApacheHttpLegacy = true;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Could not create instance of " + className, e);
            }
        }

        packageUpdaters.add(new AndroidTestRunnerSplitUpdater());

        PackageSharedLibraryUpdater[] updaterArray = packageUpdaters
                .toArray(new PackageSharedLibraryUpdater[0]);
        INSTANCE = new PackageBackwardCompatibility(hasOrgApacheHttpLegacy, updaterArray);
    }

    private final boolean mRemovedOAHLFromBCP;

    private final PackageSharedLibraryUpdater[] mPackageUpdaters;

    public PackageBackwardCompatibility(boolean removedOAHLFromBCP,
            PackageSharedLibraryUpdater[] packageUpdaters) {
        this.mRemovedOAHLFromBCP = removedOAHLFromBCP;
        this.mPackageUpdaters = packageUpdaters;
    }

    /**
     * Modify the shared libraries in the supplied {@link Package} to maintain backwards
     * compatibility.
     *
     * @param pkg the {@link Package} to modify.
     */
    @VisibleForTesting
    public static void modifySharedLibraries(Package pkg) {
        INSTANCE.updatePackage(pkg);
    }

    @Override
    public void updatePackage(Package pkg) {

        for (PackageSharedLibraryUpdater packageUpdater : mPackageUpdaters) {
            packageUpdater.updatePackage(pkg);
        }
    }

    /**
     * True if the org.apache.http.legacy has been removed the bootclasspath, false otherwise.
     */
    public static boolean removeOAHLFromBCP() {
        return INSTANCE.mRemovedOAHLFromBCP;
    }

    /**
     * Add android.test.mock dependency for any APK that depends on android.test.runner.
     *
     * <p>This is needed to maintain backwards compatibility as in previous versions of Android the
     * android.test.runner library included the classes from android.test.mock which have since
     * been split out into a separate library.
     *
     * @hide
     */
    @VisibleForTesting
    public static class AndroidTestRunnerSplitUpdater extends PackageSharedLibraryUpdater {

        @Override
        public void updatePackage(Package pkg) {
            ArrayList<String> usesLibraries = pkg.usesLibraries;
            ArrayList<String> usesOptionalLibraries = pkg.usesOptionalLibraries;

            // android.test.runner has a dependency on android.test.mock so if android.test.runner
            // is present but android.test.mock is not then add android.test.mock.
            boolean androidTestMockPresent = isLibraryPresent(
                    usesLibraries, usesOptionalLibraries, ANDROID_TEST_MOCK);
            if (ArrayUtils.contains(usesLibraries, ANDROID_TEST_RUNNER)
                    && !androidTestMockPresent) {
                usesLibraries.add(ANDROID_TEST_MOCK);
            }
            if (ArrayUtils.contains(usesOptionalLibraries, ANDROID_TEST_RUNNER)
                    && !androidTestMockPresent) {
                usesOptionalLibraries.add(ANDROID_TEST_MOCK);
            }

            pkg.usesLibraries = usesLibraries;
            pkg.usesOptionalLibraries = usesOptionalLibraries;
        }
    }

    /**
     * Remove any usages of org.apache.http.legacy from the shared library as the library is on the
     * bootclasspath.
     */
    @VisibleForTesting
    public static class RemoveUnnecessaryOrgApacheHttpLegacyLibrary
            extends PackageSharedLibraryUpdater {

        private static final String APACHE_HTTP_LEGACY = "org.apache.http.legacy";

        @Override
        public void updatePackage(Package pkg) {
            pkg.usesLibraries = ArrayUtils.remove(pkg.usesLibraries, APACHE_HTTP_LEGACY);
            pkg.usesOptionalLibraries =
                    ArrayUtils.remove(pkg.usesOptionalLibraries, APACHE_HTTP_LEGACY);
        }
    }
}
