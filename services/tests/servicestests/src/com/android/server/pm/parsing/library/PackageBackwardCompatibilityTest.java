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

package com.android.server.pm.parsing.library;

import static com.android.server.pm.parsing.library.SharedLibraryNames.ANDROID_TEST_BASE;
import static com.android.server.pm.parsing.library.SharedLibraryNames.ANDROID_TEST_MOCK;
import static com.android.server.pm.parsing.library.SharedLibraryNames.ANDROID_TEST_RUNNER;
import static com.android.server.pm.parsing.library.SharedLibraryNames.ORG_APACHE_HTTP_LEGACY;

import android.content.pm.parsing.ParsingPackage;
import android.os.Build;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.server.pm.parsing.library.PackageBackwardCompatibility.RemoveUnnecessaryAndroidTestBaseLibrary;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.PackageImpl;
import com.android.server.pm.parsing.pkg.ParsedPackage;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@Presubmit
@SmallTest
@RunWith(JUnit4.class)
public class PackageBackwardCompatibilityTest extends PackageSharedLibraryUpdaterTest {

    @Test
    public void null_usesLibraries_and_usesOptionalLibraries() {
        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .hideAsParsed());

        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .hideAsParsed())
                .hideAsFinal();

        checkBackwardsCompatibility(before, after);
    }

    /**
     * Detect when the android.test.base is not on the bootclasspath.
     *
     * <p>This test will be ignored when org.apache.http.legacy is not on the bootclasspath and
     * succeed otherwise. This allows a developer to ensure that the tests are being run in the
     * correct environment.
     */
    @Test
    public void detectWhenATBisOnBCP() {
        Assume.assumeTrue(PackageBackwardCompatibility.bootClassPathContainsATB());
    }

    /**
     * Ensures that the {@link PackageBackwardCompatibility} uses {@link OrgApacheHttpLegacyUpdater}
     * and {@link AndroidTestBaseUpdater} when necessary.
     *
     * <p>More comprehensive tests for that class can be found in
     * {@link OrgApacheHttpLegacyUpdaterTest}.
     */
    @Test
    public void targeted_at_O() {
        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .setTargetSdkVersion(Build.VERSION_CODES.O)
                .hideAsParsed());

        ParsingPackage after = PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .setTargetSdkVersion(Build.VERSION_CODES.O);

        if (!PackageBackwardCompatibility.bootClassPathContainsATB()) {
            after.addUsesLibrary(ANDROID_TEST_BASE);
        }
        after.addUsesLibrary(ORG_APACHE_HTTP_LEGACY);

        checkBackwardsCompatibility(before, ((ParsedPackage) after.hideAsParsed()).hideAsFinal());
    }

    /**
     * Ensures that the {@link PackageBackwardCompatibility} uses
     * {@link RemoveUnnecessaryAndroidTestBaseLibrary}
     * when necessary.
     *
     * <p>More comprehensive tests for that class can be found in
     * {@link RemoveUnnecessaryAndroidTestBaseLibraryTest}.
     */
    @Test
    public void android_test_base_in_usesLibraries() {
        Assume.assumeTrue("Test requires that "
                        + ANDROID_TEST_BASE + " is on the bootclasspath",
                PackageBackwardCompatibility.bootClassPathContainsATB());

        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .addUsesLibrary(ANDROID_TEST_BASE)
                .hideAsParsed());

        // android.test.base should be removed from the libraries because it is provided
        // on the bootclasspath and providing both increases start up cost unnecessarily.
        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .hideAsParsed())
                .hideAsFinal();

        checkBackwardsCompatibility(before, after);
    }

    /**
     * Ensures that the {@link PackageBackwardCompatibility} uses a
     * {@link PackageBackwardCompatibility.AndroidTestRunnerSplitUpdater}.
     *
     * <p>More comprehensive tests for that class can be found in
     * {@link AndroidTestRunnerSplitUpdaterTest}.
     */
    @Test
    public void android_test_runner_in_usesLibraries() {
        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .addUsesLibrary(ANDROID_TEST_RUNNER)
                .hideAsParsed());

        ParsingPackage after = PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT);
        if (!PackageBackwardCompatibility.bootClassPathContainsATB()) {
            after.addUsesLibrary(ANDROID_TEST_BASE);
        }
        after.addUsesLibrary(ANDROID_TEST_MOCK);
        after.addUsesLibrary(ANDROID_TEST_RUNNER);

        checkBackwardsCompatibility(before, ((ParsedPackage) after.hideAsParsed()).hideAsFinal());
    }

    /**
     * Ensures that the {@link PackageBackwardCompatibility} uses a
     * {@link ComGoogleAndroidMapsUpdater}.
     */
    @Test
    public void com_google_android_maps_in_usesLibraries() {
        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .addUsesLibrary("com.google.android.maps")
                .hideAsParsed());

        ParsingPackage after = PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT);

        checkBackwardsCompatibility(before, ((ParsedPackage) after.hideAsParsed()).hideAsFinal());
    }

    private void checkBackwardsCompatibility(ParsedPackage before, AndroidPackage after) {
        checkBackwardsCompatibility(before, after, PackageBackwardCompatibility::getInstance);
    }
}
