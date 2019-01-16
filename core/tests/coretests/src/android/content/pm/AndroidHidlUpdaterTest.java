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
import static android.content.pm.SharedLibraryNames.ANDROID_HIDL_BASE;
import static android.content.pm.SharedLibraryNames.ANDROID_HIDL_MANAGER;

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
        PackageBuilder before = builder()
                .targetSdkVersion(Build.VERSION_CODES.P);

        // Should add both HIDL libraries
        PackageBuilder after = builder()
                .targetSdkVersion(Build.VERSION_CODES.P)
                .requiredLibraries(ANDROID_HIDL_MANAGER, ANDROID_HIDL_BASE);

        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void targeted_at_P_not_empty_usesLibraries() {
        PackageBuilder before = builder()
                .targetSdkVersion(Build.VERSION_CODES.P)
                .requiredLibraries(OTHER_LIBRARY);

        // The hidl jars should be added at the start of the list because it
        // is not on the bootclasspath and the package targets pre-P.
        PackageBuilder after = builder()
                .targetSdkVersion(Build.VERSION_CODES.P)
                .requiredLibraries(ANDROID_HIDL_MANAGER, ANDROID_HIDL_BASE, OTHER_LIBRARY);

        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void targeted_at_P_in_usesLibraries() {
        PackageBuilder before = builder()
                .targetSdkVersion(Build.VERSION_CODES.P)
                .requiredLibraries(ANDROID_HIDL_MANAGER, ANDROID_HIDL_BASE);

        // No change is required because although the HIDL libraries has been removed from
        // the bootclasspath the package explicitly requests it.
        checkBackwardsCompatibility(before, before);
    }

    @Test
    public void in_usesLibraries() {
        PackageBuilder before = builder().requiredLibraries(ANDROID_HIDL_BASE);

        // Dependency is removed, it is not available.
        PackageBuilder after = builder();

        // No change is required because the package explicitly requests the HIDL libraries
        // and is targeted at the current version so does not need backwards compatibility.
        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void in_usesOptionalLibraries() {
        PackageBuilder before = builder().optionalLibraries(ANDROID_HIDL_BASE);

        // Dependency is removed, it is not available.
        PackageBuilder after = builder();

        // No change is required because the package explicitly requests the HIDL libraries
        // and is targeted at the current version so does not need backwards compatibility.
        checkBackwardsCompatibility(before, after);
    }

    private void checkBackwardsCompatibility(PackageBuilder before, PackageBuilder after) {
        checkBackwardsCompatibility(before, after, AndroidHidlUpdater::new);
    }
}
