/*
 * Copyright (C) 2018 The Android Open Source Project
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
import static android.content.pm.SharedLibraryNames.ORG_APACHE_HTTP_LEGACY;

import android.content.pm.PackageBackwardCompatibility.RemoveUnnecessaryOrgApacheHttpLegacyLibrary;
import android.os.Build;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Test for {@link RemoveUnnecessaryOrgApacheHttpLegacyLibrary}
 */
@SmallTest
@RunWith(JUnit4.class)
public class RemoveUnnecessaryOrgApacheHttpLegacyLibraryTest
        extends PackageSharedLibraryUpdaterTest {

    private static final String OTHER_LIBRARY = "other.library";

    @Test
    public void targeted_at_O() {
        PackageBuilder before = builder()
                .targetSdkVersion(Build.VERSION_CODES.O);

        // No change required.
        checkBackwardsCompatibility(before, before);
    }

    @Test
    public void targeted_at_O_not_empty_usesLibraries() {
        PackageBuilder before = builder()
                .targetSdkVersion(Build.VERSION_CODES.O)
                .requiredLibraries(OTHER_LIBRARY);

        // No change required.
        checkBackwardsCompatibility(before, before);
    }

    @Test
    public void targeted_at_O_in_usesLibraries() {
        PackageBuilder before = builder()
                .targetSdkVersion(Build.VERSION_CODES.O)
                .requiredLibraries(ORG_APACHE_HTTP_LEGACY);

        // org.apache.http.legacy should be removed from the libraries because it is provided
        // on the bootclasspath and providing both increases start up cost unnecessarily.
        PackageBuilder after = builder()
                .targetSdkVersion(Build.VERSION_CODES.O);

        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void targeted_at_O_in_usesOptionalLibraries() {
        PackageBuilder before = builder()
                .targetSdkVersion(Build.VERSION_CODES.O)
                .optionalLibraries(ORG_APACHE_HTTP_LEGACY);

        // org.apache.http.legacy should be removed from the libraries because it is provided
        // on the bootclasspath and providing both increases start up cost unnecessarily.
        PackageBuilder after = builder()
                .targetSdkVersion(Build.VERSION_CODES.O);

        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void in_usesLibraries() {
        PackageBuilder before = builder().requiredLibraries(ORG_APACHE_HTTP_LEGACY);

        // org.apache.http.legacy should be removed from the libraries because it is provided
        // on the bootclasspath and providing both increases start up cost unnecessarily.
        PackageBuilder after = builder();

        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void in_usesOptionalLibraries() {
        PackageBuilder before = builder().optionalLibraries(ORG_APACHE_HTTP_LEGACY);

        // org.apache.http.legacy should be removed from the libraries because it is provided
        // on the bootclasspath and providing both increases start up cost unnecessarily.
        PackageBuilder after = builder();

        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void in_bothLibraries() {
        PackageBuilder before = builder()
                .requiredLibraries(ORG_APACHE_HTTP_LEGACY)
                .optionalLibraries(ORG_APACHE_HTTP_LEGACY);

        // org.apache.http.legacy should be removed from the libraries because it is provided
        // on the bootclasspath and providing both increases start up cost unnecessarily.
        PackageBuilder after = builder();

        checkBackwardsCompatibility(before, after);
    }

    private void checkBackwardsCompatibility(PackageBuilder before, PackageBuilder after) {
        // TODO(b/72538146) - Cannot use constructor reference here because it is also used in
        // PackageBackwardCompatibility and that seems to create a package-private lambda in
        // android.content.pm which this then tries to reuse but fails because it cannot access
        // package-private classes/members because the test is loaded by a different ClassLoader
        // than the lambda.
        checkBackwardsCompatibility(before, after,
                () -> new RemoveUnnecessaryOrgApacheHttpLegacyLibrary());
    }
}
