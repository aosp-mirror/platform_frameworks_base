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

import static com.android.server.pm.parsing.library.SharedLibraryNames.ORG_APACHE_HTTP_LEGACY;

import android.os.Build;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.internal.pm.parsing.pkg.PackageImpl;
import com.android.internal.pm.parsing.pkg.ParsedPackage;
import com.android.server.pm.pkg.AndroidPackage;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test for {@link com.android.server.pm.parsing.library.OrgApacheHttpLegacyUpdater}
 */
@Presubmit
@SmallTest
@RunWith(OptionalClassRunner.class)
@OptionalClassRunner.OptionalClass("com.android.server.pm.parsing.library.OrgApacheHttpLegacyUpdater")
public class OrgApacheHttpLegacyUpdaterTest extends PackageSharedLibraryUpdaterTest {

    private static final String OTHER_LIBRARY = "other.library";

    @Test
    public void targeted_at_O() {
        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.O)
                .hideAsParsed());

        // Should add org.apache.http.legacy.
        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.O)
                .addUsesLibrary(ORG_APACHE_HTTP_LEGACY)
                .hideAsParsed())
                .hideAsFinal();

        checkBackwardsCompatibility(before, after, false);
    }

    @Test
    public void targeted_at_O_not_empty_usesLibraries() {
        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.O)
                .addUsesLibrary(OTHER_LIBRARY)
                .hideAsParsed());

        // The org.apache.http.legacy jar should be added at the start of the list because it
        // is not on the bootclasspath and the package targets pre-P.
        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.O)
                .addUsesLibrary(ORG_APACHE_HTTP_LEGACY)
                .addUsesLibrary(OTHER_LIBRARY)
                .hideAsParsed())
                .hideAsFinal();

        checkBackwardsCompatibility(before, after, false);
    }

    @Test
    public void targeted_at_O_in_usesLibraries() {
        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.O)
                .addUsesLibrary(ORG_APACHE_HTTP_LEGACY)
                .hideAsParsed());

        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.O)
                .addUsesLibrary(ORG_APACHE_HTTP_LEGACY)
                .hideAsParsed())
                .hideAsFinal();

        // No change is required because although org.apache.http.legacy has been removed from
        // the bootclasspath the package explicitly requests it.
        checkBackwardsCompatibility(before, after, false);
    }

    @Test
    public void targeted_at_O_in_usesOptionalLibraries() {
        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.O)
                .addUsesOptionalLibrary(ORG_APACHE_HTTP_LEGACY)
                .hideAsParsed());

        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.O)
                .addUsesOptionalLibrary(ORG_APACHE_HTTP_LEGACY)
                .hideAsParsed())
                .hideAsFinal();

        // No change is required because although org.apache.http.legacy has been removed from
        // the bootclasspath the package explicitly requests it.
        checkBackwardsCompatibility(before, after, false);
    }

    @Test
    public void in_usesLibraries() {
        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .addUsesLibrary(ORG_APACHE_HTTP_LEGACY)
                .hideAsParsed());

        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .addUsesLibrary(ORG_APACHE_HTTP_LEGACY)
                .hideAsParsed())
                .hideAsFinal();

        // No change is required because the package explicitly requests org.apache.http.legacy
        // and is targeted at the current version so does not need backwards compatibility.
        checkBackwardsCompatibility(before, after, false);
    }

    @Test
    public void in_usesOptionalLibraries() {
        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .addUsesOptionalLibrary(ORG_APACHE_HTTP_LEGACY)
                .hideAsParsed());

        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .addUsesOptionalLibrary(ORG_APACHE_HTTP_LEGACY)
                .hideAsParsed())
                .hideAsFinal();

        // No change is required because the package explicitly requests org.apache.http.legacy
        // and is targeted at the current version so does not need backwards compatibility.
        checkBackwardsCompatibility(before, after, false);
    }

    private void checkBackwardsCompatibility(ParsedPackage before, AndroidPackage after,
            boolean isSystemApp) {
        checkBackwardsCompatibility(before, after, isSystemApp, OrgApacheHttpLegacyUpdater::new);
    }
}
