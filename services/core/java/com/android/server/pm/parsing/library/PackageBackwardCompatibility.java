/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.pm.parsing.library;

import static com.android.server.pm.parsing.library.SharedLibraryNames.ANDROID_TEST_BASE;
import static com.android.server.pm.parsing.library.SharedLibraryNames.ANDROID_TEST_MOCK;
import static com.android.server.pm.parsing.library.SharedLibraryNames.ANDROID_TEST_RUNNER;
import static com.android.server.pm.parsing.library.SharedLibraryNames.ORG_APACHE_HTTP_LEGACY;

import android.content.pm.parsing.ParsingPackage;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.SystemConfig;
import com.android.server.pm.parsing.pkg.ParsedPackage;

import java.util.ArrayList;
import java.util.List;

/**
 * Modifies {@link ParsedPackage} in order to maintain backwards compatibility.
 *
 * @hide
 */
@VisibleForTesting
public class PackageBackwardCompatibility extends PackageSharedLibraryUpdater {

    private static final String TAG = PackageBackwardCompatibility.class.getSimpleName();

    private static final PackageBackwardCompatibility INSTANCE;

    static {
        final List<PackageSharedLibraryUpdater> packageUpdaters = new ArrayList<>();

        // Remove android.net.ipsec.ike library, it is added to boot classpath since Android S.
        packageUpdaters.add(new AndroidNetIpSecIkeUpdater());

        // Remove com.google.android.maps library.
        packageUpdaters.add(new ComGoogleAndroidMapsUpdater());

        // Automatically add the org.apache.http.legacy library to the app classpath if the app
        // targets < P.
        packageUpdaters.add(new OrgApacheHttpLegacyUpdater());

        packageUpdaters.add(new AndroidHidlUpdater());

        // Add this before adding AndroidTestBaseUpdater so that android.test.base comes before
        // android.test.mock.
        packageUpdaters.add(new AndroidTestRunnerSplitUpdater());

        boolean bootClassPathContainsATB = !addUpdaterForAndroidTestBase(packageUpdaters);

        // ApexSharedLibraryUpdater should be the last one, to allow modifications introduced by
        // mainline after dessert release.
        packageUpdaters.add(new ApexSharedLibraryUpdater(
                SystemConfig.getInstance().getSharedLibraries()));

        PackageSharedLibraryUpdater[] updaterArray = packageUpdaters
                .toArray(new PackageSharedLibraryUpdater[0]);
        INSTANCE = new PackageBackwardCompatibility(
                bootClassPathContainsATB, updaterArray);
    }

    /**
     * Attempt to load and add the optional updater that will only be available when
     * REMOVE_ATB_FROM_BCP=true. If that could not be found then add the default updater that
     * will remove any references to org.apache.http.library from the package so that
     * it does not try and load the library when it is on the bootclasspath.
     *
     * TODO:(b/135203078): Find a better way to do this.
     */
    private static boolean addUpdaterForAndroidTestBase(
            List<PackageSharedLibraryUpdater> packageUpdaters) {
        boolean hasClass = false;
        String className = "android.content.pm.AndroidTestBaseUpdater";
        try {
            Class clazz = ParsingPackage.class.getClassLoader().loadClass(className);
            hasClass = clazz != null;
            Log.i(TAG, "Loaded " + className);
        } catch (ClassNotFoundException e) {
            Log.i(TAG, "Could not find " + className + ", ignoring");
        }

        if (hasClass) {
            packageUpdaters.add(new AndroidTestBaseUpdater());
        } else {
            packageUpdaters.add(new RemoveUnnecessaryAndroidTestBaseLibrary());
        }
        return hasClass;
    }

    @VisibleForTesting
    public static PackageSharedLibraryUpdater getInstance() {
        return INSTANCE;
    }

    private final boolean mBootClassPathContainsATB;

    private final PackageSharedLibraryUpdater[] mPackageUpdaters;

    @VisibleForTesting
    PackageSharedLibraryUpdater[] getPackageUpdaters() {
        return mPackageUpdaters;
    }

    private PackageBackwardCompatibility(
            boolean bootClassPathContainsATB, PackageSharedLibraryUpdater[] packageUpdaters) {
        this.mBootClassPathContainsATB = bootClassPathContainsATB;
        this.mPackageUpdaters = packageUpdaters;
    }

    /**
     * Modify the shared libraries in the supplied {@link ParsedPackage} to maintain backwards
     * compatibility.
     *
     * @param parsedPackage the {@link ParsedPackage} to modify.
     */
    @VisibleForTesting
    public static void modifySharedLibraries(ParsedPackage parsedPackage,
            boolean isUpdatedSystemApp) {
        INSTANCE.updatePackage(parsedPackage, isUpdatedSystemApp);
    }

    @Override
    public void updatePackage(ParsedPackage parsedPackage, boolean isUpdatedSystemApp) {
        for (PackageSharedLibraryUpdater packageUpdater : mPackageUpdaters) {
            packageUpdater.updatePackage(parsedPackage, isUpdatedSystemApp);
        }
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
        public void updatePackage(ParsedPackage parsedPackage, boolean isUpdatedSystemApp) {
            // android.test.runner has a dependency on android.test.mock so if android.test.runner
            // is present but android.test.mock is not then add android.test.mock.
            prefixImplicitDependency(parsedPackage, ANDROID_TEST_RUNNER, ANDROID_TEST_MOCK);
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
        public void updatePackage(ParsedPackage parsedPackage, boolean isUpdatedSystemApp) {
            removeLibrary(parsedPackage, ORG_APACHE_HTTP_LEGACY);
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
        public void updatePackage(ParsedPackage parsedPackage, boolean isUpdatedSystemApp) {
            removeLibrary(parsedPackage, ANDROID_TEST_BASE);
        }
    }
}
