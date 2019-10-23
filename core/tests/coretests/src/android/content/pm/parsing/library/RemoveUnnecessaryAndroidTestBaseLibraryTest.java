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

import static android.content.pm.parsing.library.SharedLibraryNames.ANDROID_TEST_BASE;

import android.content.pm.parsing.AndroidPackage;
import android.content.pm.parsing.PackageImpl;
import android.content.pm.parsing.ParsedPackage;
import android.content.pm.parsing.library.PackageBackwardCompatibility.RemoveUnnecessaryAndroidTestBaseLibrary;
import android.os.Build;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Test for {@link RemoveUnnecessaryAndroidTestBaseLibrary}
 */
@SmallTest
@RunWith(JUnit4.class)
public class RemoveUnnecessaryAndroidTestBaseLibraryTest
        extends PackageSharedLibraryUpdaterTest {

    private static final String OTHER_LIBRARY = "other.library";

    @Test
    public void targeted_at_O() {
        ParsedPackage before = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.O)
                .hideAsParsed();

        AndroidPackage after = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.O)
                .hideAsParsed()
                .hideAsFinal();

        // No change required.
        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void targeted_at_O_not_empty_usesLibraries() {
        ParsedPackage before = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.O)
                .addUsesLibrary(OTHER_LIBRARY)
                .hideAsParsed();

        AndroidPackage after = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.O)
                .addUsesLibrary(OTHER_LIBRARY)
                .hideAsParsed()
                .hideAsFinal();

        // No change required.
        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void targeted_at_O_in_usesLibraries() {
        ParsedPackage before = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.O)
                .addUsesLibrary(ANDROID_TEST_BASE)
                .hideAsParsed();

        // android.test.base should be removed from the libraries because it is provided
        // on the bootclasspath and providing both increases start up cost unnecessarily.
        AndroidPackage after = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.O)
                .hideAsParsed()
                .hideAsFinal();

        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void targeted_at_O_in_usesOptionalLibraries() {
        ParsedPackage before = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.O)
                .addUsesOptionalLibrary(ANDROID_TEST_BASE)
                .hideAsParsed();

        // android.test.base should be removed from the libraries because it is provided
        // on the bootclasspath and providing both increases start up cost unnecessarily.
        AndroidPackage after = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.O)
                .hideAsParsed()
                .hideAsFinal();

        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void in_usesLibraries() {
        ParsedPackage before = PackageImpl.forParsing(PACKAGE_NAME)
                .addUsesLibrary(ANDROID_TEST_BASE)
                .hideAsParsed();

        // android.test.base should be removed from the libraries because it is provided
        // on the bootclasspath and providing both increases start up cost unnecessarily.
        AndroidPackage after = PackageImpl.forParsing(PACKAGE_NAME)
                .hideAsParsed()
                .hideAsFinal();

        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void in_usesOptionalLibraries() {
        ParsedPackage before = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .addUsesOptionalLibrary(ANDROID_TEST_BASE)
                .hideAsParsed();

        // android.test.base should be removed from the libraries because it is provided
        // on the bootclasspath and providing both increases start up cost unnecessarily.
        AndroidPackage after = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .hideAsParsed()
                .hideAsFinal();

        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void in_bothLibraries() {
        ParsedPackage before = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .addUsesLibrary(ANDROID_TEST_BASE)
                .addUsesOptionalLibrary(ANDROID_TEST_BASE)
                .hideAsParsed();

        // android.test.base should be removed from the libraries because it is provided
        // on the bootclasspath and providing both increases start up cost unnecessarily.
        AndroidPackage after = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .hideAsParsed()
                .hideAsFinal();

        checkBackwardsCompatibility(before, after);
    }

    private void checkBackwardsCompatibility(ParsedPackage before, AndroidPackage after) {
        // TODO(b/72538146) - Cannot use constructor reference here because it is also used in
        // PackageBackwardCompatibility and that seems to create a package-private lambda in
        // android.content.pm which this then tries to reuse but fails because it cannot access
        // package-private classes/members because the test is loaded by a different ClassLoader
        // than the lambda.
        checkBackwardsCompatibility(before, after,
                () -> new RemoveUnnecessaryAndroidTestBaseLibrary());
    }

}
