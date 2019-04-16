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
import static android.content.pm.SharedLibraryNames.ANDROID_TEST_BASE;

import android.os.Build;
import android.support.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test for {@link AndroidTestBaseUpdater}
 */
@SmallTest
@RunWith(OptionalClassRunner.class)
@OptionalClassRunner.OptionalClass("android.content.pm.AndroidTestBaseUpdater")
public class AndroidTestBaseUpdaterTest extends PackageSharedLibraryUpdaterTest {

    private static final String OTHER_LIBRARY = "other.library";

    @Test
    public void targeted_at_P() {
        PackageBuilder before = builder()
                .targetSdkVersion(Build.VERSION_CODES.P);

        // Should add org.apache.http.legacy.
        PackageBuilder after = builder()
                .targetSdkVersion(Build.VERSION_CODES.P)
                .requiredLibraries(ANDROID_TEST_BASE);

        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void targeted_at_P_not_empty_usesLibraries() {
        PackageBuilder before = builder()
                .targetSdkVersion(Build.VERSION_CODES.P)
                .requiredLibraries(OTHER_LIBRARY);

        // The org.apache.http.legacy jar should be added at the start of the list because it
        // is not on the bootclasspath and the package targets pre-P.
        PackageBuilder after = builder()
                .targetSdkVersion(Build.VERSION_CODES.P)
                .requiredLibraries(ANDROID_TEST_BASE, OTHER_LIBRARY);

        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void targeted_at_P_in_usesLibraries() {
        PackageBuilder before = builder()
                .targetSdkVersion(Build.VERSION_CODES.P)
                .requiredLibraries(ANDROID_TEST_BASE);

        // No change is required because although org.apache.http.legacy has been removed from
        // the bootclasspath the package explicitly requests it.
        checkBackwardsCompatibility(before, before);
    }

    @Test
    public void targeted_at_P_in_usesOptionalLibraries() {
        PackageBuilder before = builder()
                .targetSdkVersion(Build.VERSION_CODES.P)
                .optionalLibraries(ANDROID_TEST_BASE);

        // No change is required because although org.apache.http.legacy has been removed from
        // the bootclasspath the package explicitly requests it.
        checkBackwardsCompatibility(before, before);
    }

    @Test
    public void in_usesLibraries() {
        PackageBuilder before = builder().requiredLibraries(ANDROID_TEST_BASE);

        // No change is required because the package explicitly requests org.apache.http.legacy
        // and is targeted at the current version so does not need backwards compatibility.
        checkBackwardsCompatibility(before, before);
    }

    @Test
    public void in_usesOptionalLibraries() {
        PackageBuilder before = builder().optionalLibraries(ANDROID_TEST_BASE);

        // No change is required because the package explicitly requests org.apache.http.legacy
        // and is targeted at the current version so does not need backwards compatibility.
        checkBackwardsCompatibility(before, before);
    }

    private void checkBackwardsCompatibility(PackageBuilder before, PackageBuilder after) {
        checkBackwardsCompatibility(before, after, AndroidTestBaseUpdater::new);
    }
}
