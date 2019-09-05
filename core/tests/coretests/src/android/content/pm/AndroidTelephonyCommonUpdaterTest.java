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
import static android.content.pm.SharedLibraryNames.ANDROID_TELEPHONY_COMMON;

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
public class AndroidTelephonyCommonUpdaterTest extends PackageSharedLibraryUpdaterTest {

    private static final String OTHER_LIBRARY = "other.library";
    private static final String PHONE_UID = "android.uid.phone";

    @Test
    public void targeted_at_Q() {
        PackageBuilder before = builder()
                .targetSdkVersion(Build.VERSION_CODES.Q);

        PackageBuilder after = builder().targetSdkVersion(Build.VERSION_CODES.Q)
            .requiredLibraries(ANDROID_TELEPHONY_COMMON);

        // Should add telephony-common libraries
        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void targeted_at_Q_phoneUID() {
        PackageBuilder before = builder().setSharedUid(PHONE_UID)
                .targetSdkVersion(Build.VERSION_CODES.Q);

        // Should add telephony-common libraries
        PackageBuilder after = builder().setSharedUid(PHONE_UID)
                .targetSdkVersion(Build.VERSION_CODES.Q)
                .requiredLibraries(ANDROID_TELEPHONY_COMMON);

        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void targeted_at_Q_not_empty_usesLibraries() {
        PackageBuilder before = builder()
                .targetSdkVersion(Build.VERSION_CODES.Q)
                .requiredLibraries(OTHER_LIBRARY);

        // no change
        checkBackwardsCompatibility(before, before);
    }

    @Test
    public void targeted_at_Q_not_empty_usesLibraries_phoneUID() {
        PackageBuilder before = builder().setSharedUid(PHONE_UID)
                .targetSdkVersion(Build.VERSION_CODES.Q)
                .requiredLibraries(OTHER_LIBRARY);

        // The telephony-common jars should be added at the start of the list because it
        // is not on the bootclasspath and the package targets pre-R.
        PackageBuilder after = builder().setSharedUid(PHONE_UID)
                .targetSdkVersion(Build.VERSION_CODES.Q)
                .requiredLibraries(ANDROID_TELEPHONY_COMMON, OTHER_LIBRARY);

        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void targeted_at_R_in_usesLibraries() {
        PackageBuilder before = builder()
                .targetSdkVersion(Build.VERSION_CODES.Q + 1)
                .requiredLibraries(ANDROID_TELEPHONY_COMMON);

        PackageBuilder after = builder()
                .targetSdkVersion(Build.VERSION_CODES.Q + 1);

        // Libraries are removed because they are not available for apps target >= R and not run
        // on phone-uid
        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void targeted_at_Q_in_usesLibraries() {
        PackageBuilder before = builder().asSystemApp()
                .targetSdkVersion(Build.VERSION_CODES.Q)
                .requiredLibraries(ANDROID_TELEPHONY_COMMON);

        // No change is required because the package explicitly requests the telephony libraries
        // and is targeted at the current version so does not need backwards compatibility.
        checkBackwardsCompatibility(before, before);
    }


    @Test
    public void targeted_at_R_in_usesOptionalLibraries() {
        PackageBuilder before = builder().targetSdkVersion(Build.VERSION_CODES.Q + 1)
            .optionalLibraries(ANDROID_TELEPHONY_COMMON);

        // Dependency is removed, it is not available.
        PackageBuilder after = builder().targetSdkVersion(Build.VERSION_CODES.Q + 1);

        // Libraries are removed because they are not available for apps targeting Q+
        checkBackwardsCompatibility(before, after);
    }

    @Test
    public void targeted_at_R() {
        PackageBuilder before = builder()
            .targetSdkVersion(Build.VERSION_CODES.Q + 1);

        // no change
        checkBackwardsCompatibility(before, before);
    }

    private void checkBackwardsCompatibility(PackageBuilder before, PackageBuilder after) {
        checkBackwardsCompatibility(before, after, AndroidTelephonyCommonUpdater::new);
    }
}
