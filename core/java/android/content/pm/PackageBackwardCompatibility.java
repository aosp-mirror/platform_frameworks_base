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

import static android.content.pm.SharedLibraryNames.ANDROID_TEST_BASE;
import static android.content.pm.SharedLibraryNames.ANDROID_TEST_MOCK;
import static android.content.pm.SharedLibraryNames.ANDROID_TEST_RUNNER;
import static android.content.pm.SharedLibraryNames.ORG_APACHE_HTTP_LEGACY;

import android.content.pm.PackageParser.Package;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Modifies {@link Package} in order to maintain backwards compatibility.
 *
 * @hide
 */
@VisibleForTesting
public class PackageBackwardCompatibility extends PackageSharedLibraryUpdater {

    private static final String TAG = PackageBackwardCompatibility.class.getSimpleName();

    private static final PackageBackwardCompatibility INSTANCE;

    static {
        final List<PackageSharedLibraryUpdater> packageUpdaters = new ArrayList<>();

        // Attempt to load and add the optional updater that will only be available when
        // REMOVE_OAHL_FROM_BCP=true. If that could not be found then add the default updater that
        // will remove any references to org.apache.http.library from the package so that it does
        // not try and load the library when it is on the bootclasspath.
        boolean bootClassPathContainsOAHL = !addOptionalUpdater(packageUpdaters,
                "android.content.pm.OrgApacheHttpLegacyUpdater",
                RemoveUnnecessaryOrgApacheHttpLegacyLibrary::new);

        // Add this before adding AndroidTestBaseUpdater so that android.test.base comes before
        // android.test.mock.
        packageUpdaters.add(new AndroidTestRunnerSplitUpdater());

        // Attempt to load and add the optional updater that will only be available when
        // REMOVE_ATB_FROM_BCP=true. If that could not be found then add the default updater that
        // will remove any references to org.apache.http.library from the package so that it does
        // not try and load the library when it is on the bootclasspath.
        boolean bootClassPathContainsATB = !addOptionalUpdater(packageUpdaters,
                "android.content.pm.AndroidTestBaseUpdater",
                RemoveUnnecessaryAndroidTestBaseLibrary::new);

        PackageSharedLibraryUpdater[] updaterArray = packageUpdaters
                .toArray(new PackageSharedLibraryUpdater[0]);
        INSTANCE = new PackageBackwardCompatibility(
                bootClassPathContainsOAHL, bootClassPathContainsATB, updaterArray);
    }

    /**
     * Add an optional {@link PackageSharedLibraryUpdater} instance to the list, if it could not be
     * found then add a default instance instead.
     *
     * @param packageUpdaters the list to update.
     * @param className the name of the optional class.
     * @param defaultUpdater the supplier of the default instance.
     * @return true if the optional updater was added false otherwise.
     */
    private static boolean addOptionalUpdater(List<PackageSharedLibraryUpdater> packageUpdaters,
            String className, Supplier<PackageSharedLibraryUpdater> defaultUpdater) {
        Class<? extends PackageSharedLibraryUpdater> clazz;
        try {
            clazz = (PackageBackwardCompatibility.class.getClassLoader()
                    .loadClass(className)
                    .asSubclass(PackageSharedLibraryUpdater.class));
            Log.i(TAG, "Loaded " + className);
        } catch (ClassNotFoundException e) {
            Log.i(TAG, "Could not find " + className + ", ignoring");
            clazz = null;
        }

        boolean usedOptional = false;
        PackageSharedLibraryUpdater updater;
        if (clazz == null) {
            updater = defaultUpdater.get();
        } else {
            try {
                updater = clazz.getConstructor().newInstance();
                usedOptional = true;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Could not create instance of " + className, e);
            }
        }
        packageUpdaters.add(updater);
        return usedOptional;
    }

    @VisibleForTesting
    public static PackageSharedLibraryUpdater getInstance() {
        return INSTANCE;
    }

    private final boolean mBootClassPathContainsOAHL;

    private final boolean mBootClassPathContainsATB;

    private final PackageSharedLibraryUpdater[] mPackageUpdaters;

    public PackageBackwardCompatibility(boolean bootClassPathContainsOAHL,
            boolean bootClassPathContainsATB, PackageSharedLibraryUpdater[] packageUpdaters) {
        this.mBootClassPathContainsOAHL = bootClassPathContainsOAHL;
        this.mBootClassPathContainsATB = bootClassPathContainsATB;
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
     * True if the org.apache.http.legacy is on the bootclasspath, false otherwise.
     */
    @VisibleForTesting
    public static boolean bootClassPathContainsOAHL() {
        return INSTANCE.mBootClassPathContainsOAHL;
    }

    /**
     * True if the android.test.base is on the bootclasspath, false otherwise.
     */
    @VisibleForTesting
    public static boolean bootClassPathContainsATB() {
        return INSTANCE.mBootClassPathContainsATB;
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
            // android.test.runner has a dependency on android.test.mock so if android.test.runner
            // is present but android.test.mock is not then add android.test.mock.
            prefixImplicitDependency(pkg, ANDROID_TEST_RUNNER, ANDROID_TEST_MOCK);
        }
    }

    /**
     * Remove any usages of org.apache.http.legacy from the shared library as the library is on the
     * bootclasspath.
     */
    @VisibleForTesting
    public static class RemoveUnnecessaryOrgApacheHttpLegacyLibrary
            extends PackageSharedLibraryUpdater {

        @Override
        public void updatePackage(Package pkg) {
            removeLibrary(pkg, ORG_APACHE_HTTP_LEGACY);
        }

    }

    /**
     * Remove any usages of android.test.base from the shared library as the library is on the
     * bootclasspath.
     */
    @VisibleForTesting
    public static class RemoveUnnecessaryAndroidTestBaseLibrary
            extends PackageSharedLibraryUpdater {

        @Override
        public void updatePackage(Package pkg) {
            removeLibrary(pkg, ANDROID_TEST_BASE);
        }
    }
}
