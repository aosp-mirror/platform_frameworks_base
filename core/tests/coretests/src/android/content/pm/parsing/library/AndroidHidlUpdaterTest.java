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

package android.content.pm.parsing.library;

import static android.content.pm.parsing.library.SharedLibraryNames.ANDROID_HIDL_BASE;
import static android.content.pm.parsing.library.SharedLibraryNames.ANDROID_HIDL_MANAGER;

import android.content.pm.parsing.AndroidPackage;
import android.content.pm.parsing.PackageImpl;
import android.content.pm.parsing.ParsedPackage;
import android.os.Build;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Test for {@link AndroidHidlUpdater}
 */
@SmallTest
@RunWith(JUnit4.class)
public class AndroidHidlUpdaterTest extends PackageSharedLibraryUpdaterTest {

    private static final String OTHER_LIBRARY = "other.library";

    @Test
    public void targeted_at_P() {
        ParsedPackage before = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.P)
                .hideAsParsed();

        AndroidPackage after = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.P)
                .hideAsParsed()
                .hideAsFinal();

        // no change, not system
        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void targeted_at_P_system() {
        ParsedPackage before = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.P)
                .hideAsParsed()
                .setSystem(true);

        // Should add both HIDL libraries
        AndroidPackage after = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.P)
                .addUsesLibrary(ANDROID_HIDL_MANAGER)
                .addUsesLibrary(ANDROID_HIDL_BASE)
                .hideAsParsed()
                .setSystem(true)
                .hideAsFinal();

        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void targeted_at_P_not_empty_usesLibraries() {
        ParsedPackage before = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.P)
                .addUsesLibrary(OTHER_LIBRARY)
                .hideAsParsed();

        AndroidPackage after = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.P)
                .addUsesLibrary(OTHER_LIBRARY)
                .hideAsParsed()
                .hideAsFinal();

        // no change, not system
        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void targeted_at_P_not_empty_usesLibraries_system() {
        ParsedPackage before = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.P)
                .addUsesLibrary(OTHER_LIBRARY)
                .hideAsParsed()
                .setSystem(true);

        // The hidl jars should be added at the start of the list because it
        // is not on the bootclasspath and the package targets pre-P.
        AndroidPackage after = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.P)
                .addUsesLibrary(ANDROID_HIDL_MANAGER)
                .addUsesLibrary(ANDROID_HIDL_BASE)
                .addUsesLibrary(OTHER_LIBRARY)
                .hideAsParsed()
                .setSystem(true)
                .hideAsFinal();

        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void targeted_at_P_in_usesLibraries() {
        ParsedPackage before = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.P)
                .addUsesLibrary(ANDROID_HIDL_MANAGER)
                .addUsesLibrary(ANDROID_HIDL_BASE)
                .hideAsParsed();

        AndroidPackage after = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.P)
                .hideAsParsed()
                .hideAsFinal();

        // Libraries are removed because they are not available for non-system apps
        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void targeted_at_P_in_usesLibraries_system() {
        ParsedPackage before = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.P)
                .addUsesLibrary(ANDROID_HIDL_MANAGER)
                .addUsesLibrary(ANDROID_HIDL_BASE)
                .hideAsParsed()
                .setSystem(true);

        AndroidPackage after = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.P)
                .addUsesLibrary(ANDROID_HIDL_MANAGER)
                .addUsesLibrary(ANDROID_HIDL_BASE)
                .hideAsParsed()
                .setSystem(true)
                .hideAsFinal();

        // No change is required because the package explicitly requests the HIDL libraries
        // and is targeted at the current version so does not need backwards compatibility.
        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void in_usesLibraries() {
        ParsedPackage before = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .addUsesLibrary(ANDROID_HIDL_BASE)
                .hideAsParsed();

        // Dependency is removed, it is not available.
        AndroidPackage after = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .hideAsParsed()
                .hideAsFinal();

        // Libraries are removed because they are not available for apps targeting Q+
        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void in_usesOptionalLibraries() {
        ParsedPackage before = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .addUsesOptionalLibrary(ANDROID_HIDL_BASE)
                .hideAsParsed();

        // Dependency is removed, it is not available.
        AndroidPackage after = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .hideAsParsed()
                .hideAsFinal();

        // Libraries are removed because they are not available for apps targeting Q+
        checkBackwardsCompatibility(before, after);
    }

    private void checkBackwardsCompatibility(ParsedPackage before, AndroidPackage after) {
        checkBackwardsCompatibility(before, after, AndroidHidlUpdater::new);
    }
}
