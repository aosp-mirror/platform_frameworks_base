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
import static android.content.pm.SharedLibraryNames.ANDROID_TEST_MOCK;
import static android.content.pm.SharedLibraryNames.ANDROID_TEST_RUNNER;

import android.content.pm.PackageBackwardCompatibility.AndroidTestRunnerSplitUpdater;
import android.support.test.filters.SmallTest;

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
        PackageBuilder before = builder().optionalLibraries(ANDROID_TEST_RUNNER);

        PackageBuilder after = builder()
                .optionalLibraries(ANDROID_TEST_MOCK, ANDROID_TEST_RUNNER);

        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void android_test_runner_in_usesLibraries_android_test_mock_in_usesOptionalLibraries() {
        PackageBuilder before = builder()
                .requiredLibraries(ANDROID_TEST_RUNNER)
                .optionalLibraries(ANDROID_TEST_MOCK);

        PackageBuilder after = builder()
                .requiredLibraries(ANDROID_TEST_RUNNER)
                .optionalLibraries(ANDROID_TEST_MOCK);

        checkBackwardsCompatibility(before, after);
    }

    private void checkBackwardsCompatibility(PackageBuilder before, PackageBuilder after) {
        checkBackwardsCompatibility(before, after, AndroidTestRunnerSplitUpdater::new);
    }
}
