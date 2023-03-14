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

import android.os.Build;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.PackageImpl;
import com.android.server.pm.parsing.pkg.ParsedPackage;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test for {@link AndroidTestBaseUpdater}
 */
@Presubmit
@SmallTest
@RunWith(OptionalClassRunner.class)
@OptionalClassRunner.OptionalClass("com.android.server.pm.parsing.library.AndroidTestBaseUpdater")
public class AndroidTestBaseUpdaterTest extends PackageSharedLibraryUpdaterTest {

    private static final String OTHER_LIBRARY = "other.library";

    @Test
    public void targeted_at_Q() {
        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.Q)
                .hideAsParsed());

        // Should add org.apache.http.legacy.
        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.Q)
                .addUsesLibrary(ANDROID_TEST_BASE)
                .hideAsParsed())
                .hideAsFinal();

        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void targeted_at_Q_not_empty_usesLibraries() {
        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.Q)
                .addUsesLibrary(OTHER_LIBRARY)
                .hideAsParsed());

        // The org.apache.http.legacy jar should be added at the start of the list because it
        // is not on the bootclasspath and the package targets pre-Q.
        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.Q)
                .addUsesLibrary(ANDROID_TEST_BASE)
                .addUsesLibrary(OTHER_LIBRARY)
                .hideAsParsed())
                .hideAsFinal();

        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void targeted_at_Q_in_usesLibraries() {
        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.Q)
                .addUsesLibrary(ANDROID_TEST_BASE)
                .hideAsParsed());

        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.Q)
                .addUsesLibrary(ANDROID_TEST_BASE)
                .hideAsParsed())
                .hideAsFinal();

        // No change is required because although org.apache.http.legacy has been removed from
        // the bootclasspath the package explicitly requests it.
        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void targeted_at_Q_in_usesOptionalLibraries() {
        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.Q)
                .addUsesOptionalLibrary(ANDROID_TEST_BASE)
                .hideAsParsed());

        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.Q)
                .addUsesOptionalLibrary(ANDROID_TEST_BASE)
                .hideAsParsed())
                .hideAsFinal();

        // No change is required because although org.apache.http.legacy has been removed from
        // the bootclasspath the package explicitly requests it.
        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void in_usesLibraries() {
        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .addUsesLibrary(ANDROID_TEST_BASE)
                .hideAsParsed());

        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .addUsesLibrary(ANDROID_TEST_BASE)
                .hideAsParsed())
                .hideAsFinal();

        // No change is required because the package explicitly requests org.apache.http.legacy
        // and is targeted at the current version so does not need backwards compatibility.
        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void in_usesOptionalLibraries() {
        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .addUsesOptionalLibrary(ANDROID_TEST_BASE)
                .hideAsParsed());

        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .addUsesOptionalLibrary(ANDROID_TEST_BASE)
                .hideAsParsed())
                .hideAsFinal();

        // No change is required because the package explicitly requests org.apache.http.legacy
        // and is targeted at the current version so does not need backwards compatibility.
        checkBackwardsCompatibility(before, after);
    }

    private void checkBackwardsCompatibility(ParsedPackage before, AndroidPackage after) {
        checkBackwardsCompatibility(before, after, AndroidTestBaseUpdater::new);
    }
}
