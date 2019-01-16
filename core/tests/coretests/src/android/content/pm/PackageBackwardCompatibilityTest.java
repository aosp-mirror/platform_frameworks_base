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

import static android.content.pm.PackageBuilder.builder;
import static android.content.pm.SharedLibraryNames.ANDROID_TEST_BASE;
import static android.content.pm.SharedLibraryNames.ANDROID_TEST_MOCK;
import static android.content.pm.SharedLibraryNames.ANDROID_TEST_RUNNER;
import static android.content.pm.SharedLibraryNames.ORG_APACHE_HTTP_LEGACY;

import android.content.pm.PackageBackwardCompatibility.RemoveUnnecessaryAndroidTestBaseLibrary;
import android.os.Build;

import androidx.test.filters.SmallTest;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(JUnit4.class)
public class PackageBackwardCompatibilityTest extends PackageSharedLibraryUpdaterTest {

    @Test
    public void null_usesLibraries_and_usesOptionalLibraries() {
        PackageBuilder before = builder();
        PackageBuilder after = builder();

        checkBackwardsCompatibility(before, after);
    }

    /**
     * Detect when the org.apache.http.legacy is not on the bootclasspath.
     *
     * <p>This test will be ignored when org.apache.http.legacy is not on the bootclasspath and
     * succeed otherwise. This allows a developer to ensure that the tests are being
     */
    @Test
    public void detectWhenOAHLisOnBCP() {
        Assume.assumeTrue(PackageBackwardCompatibility.bootClassPathContainsOAHL());
    }

    /**
     * Detect when the android.test.base is not on the bootclasspath.
     *
     * <p>This test will be ignored when org.apache.http.legacy is not on the bootclasspath and
     * succeed otherwise. This allows a developer to ensure that the tests are being
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
        PackageBuilder before = builder()
                .targetSdkVersion(Build.VERSION_CODES.O);

        List<String> expected = new ArrayList<>();
        if (!PackageBackwardCompatibility.bootClassPathContainsATB()) {
            expected.add(ANDROID_TEST_BASE);
        }
        if (!PackageBackwardCompatibility.bootClassPathContainsOAHL()) {
            expected.add(ORG_APACHE_HTTP_LEGACY);
        }

        PackageBuilder after = builder()
                .targetSdkVersion(Build.VERSION_CODES.O)
                .requiredLibraries(expected);

        checkBackwardsCompatibility(before, after);
    }

    /**
     * Ensures that the {@link PackageBackwardCompatibility} uses
     * {@link RemoveUnnecessaryOrgApacheHttpLegacyLibraryTest}
     * when necessary.
     *
     * <p>More comprehensive tests for that class can be found in
     * {@link RemoveUnnecessaryOrgApacheHttpLegacyLibraryTest}.
     */
    @Test
    public void org_apache_http_legacy_in_usesLibraries() {
        Assume.assumeTrue("Test requires that "
                        + ORG_APACHE_HTTP_LEGACY + " is on the bootclasspath",
                PackageBackwardCompatibility.bootClassPathContainsOAHL());

        PackageBuilder before = builder()
                .requiredLibraries(ORG_APACHE_HTTP_LEGACY);

        // org.apache.http.legacy should be removed from the libraries because it is provided
        // on the bootclasspath and providing both increases start up cost unnecessarily.
        PackageBuilder after = builder();

        checkBackwardsCompatibility(before, after);
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

        PackageBuilder before = builder()
                .requiredLibraries(ANDROID_TEST_BASE);

        // android.test.base should be removed from the libraries because it is provided
        // on the bootclasspath and providing both increases start up cost unnecessarily.
        PackageBuilder after = builder();

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
        PackageBuilder before = builder().requiredLibraries(ANDROID_TEST_RUNNER);

        List<String> expected = new ArrayList<>();
        if (!PackageBackwardCompatibility.bootClassPathContainsATB()) {
            expected.add(ANDROID_TEST_BASE);
        }
        expected.add(ANDROID_TEST_MOCK);
        expected.add(ANDROID_TEST_RUNNER);

        PackageBuilder after = builder()
                .requiredLibraries(expected);

        checkBackwardsCompatibility(before, after);
    }

    private void checkBackwardsCompatibility(PackageBuilder before, PackageBuilder after) {
        checkBackwardsCompatibility(before, after, PackageBackwardCompatibility::getInstance);
    }
}
