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

import static com.android.server.pm.parsing.library.SharedLibraryNames.ANDROID_TEST_MOCK;
import static com.android.server.pm.parsing.library.SharedLibraryNames.ANDROID_TEST_RUNNER;

import android.os.Build;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.internal.pm.parsing.pkg.PackageImpl;
import com.android.internal.pm.parsing.pkg.ParsedPackage;
import com.android.server.pm.parsing.library.PackageBackwardCompatibility.AndroidTestRunnerSplitUpdater;
import com.android.server.pm.pkg.AndroidPackage;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Test for {@link AndroidTestRunnerSplitUpdater}
 */
@Presubmit
@SmallTest
@RunWith(JUnit4.class)
public class AndroidTestRunnerSplitUpdaterTest extends PackageSharedLibraryUpdaterTest {

    @Test
    public void android_test_runner_in_usesOptionalLibraries() {
        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .addUsesOptionalLibrary(ANDROID_TEST_RUNNER)
                .hideAsParsed());

        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .addUsesOptionalLibrary(ANDROID_TEST_MOCK)
                .addUsesOptionalLibrary(ANDROID_TEST_RUNNER)
                .hideAsParsed())
                .hideAsFinal();

        checkBackwardsCompatibility(before, after, false);
    }

    @Test
    public void android_test_runner_in_usesLibraries_android_test_mock_in_usesOptionalLibraries() {
        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .addUsesLibrary(ANDROID_TEST_RUNNER)
                .addUsesOptionalLibrary(ANDROID_TEST_MOCK)
                .hideAsParsed());

        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .addUsesLibrary(ANDROID_TEST_RUNNER)
                .addUsesOptionalLibrary(ANDROID_TEST_MOCK)
                .hideAsParsed())
                .hideAsFinal();

        checkBackwardsCompatibility(before, after, false);
    }

    private void checkBackwardsCompatibility(ParsedPackage before, AndroidPackage after,
            boolean isSystemApp) {
        checkBackwardsCompatibility(before, after, isSystemApp, AndroidTestRunnerSplitUpdater::new);
    }
}
