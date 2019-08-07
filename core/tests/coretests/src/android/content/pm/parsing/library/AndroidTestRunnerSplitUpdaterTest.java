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

import static android.content.pm.parsing.library.SharedLibraryNames.ANDROID_TEST_MOCK;
import static android.content.pm.parsing.library.SharedLibraryNames.ANDROID_TEST_RUNNER;

import android.content.pm.parsing.AndroidPackage;
import android.content.pm.parsing.PackageImpl;
import android.content.pm.parsing.ParsedPackage;
import android.content.pm.parsing.library.PackageBackwardCompatibility.AndroidTestRunnerSplitUpdater;
import android.os.Build;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Test for {@link AndroidTestRunnerSplitUpdater}
 */
@SmallTest
@RunWith(JUnit4.class)
public class AndroidTestRunnerSplitUpdaterTest extends PackageSharedLibraryUpdaterTest {

    @Test
    public void android_test_runner_in_usesOptionalLibraries() {
        ParsedPackage before = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .addUsesOptionalLibrary(ANDROID_TEST_RUNNER)
                .hideAsParsed();

        AndroidPackage after = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .addUsesOptionalLibrary(ANDROID_TEST_MOCK)
                .addUsesOptionalLibrary(ANDROID_TEST_RUNNER)
                .hideAsParsed()
                .hideAsFinal();

        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void android_test_runner_in_usesLibraries_android_test_mock_in_usesOptionalLibraries() {
        ParsedPackage before = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .addUsesLibrary(ANDROID_TEST_RUNNER)
                .addUsesOptionalLibrary(ANDROID_TEST_MOCK)
                .hideAsParsed();

        AndroidPackage after = PackageImpl.forParsing(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .addUsesLibrary(ANDROID_TEST_RUNNER)
                .addUsesOptionalLibrary(ANDROID_TEST_MOCK)
                .hideAsParsed()
                .hideAsFinal();

        checkBackwardsCompatibility(before, after);
    }

    private void checkBackwardsCompatibility(ParsedPackage before, AndroidPackage after) {
        checkBackwardsCompatibility(before, after, AndroidTestRunnerSplitUpdater::new);
    }
}
