/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.PackageParser.Package;
import android.os.Build;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import java.util.ArrayList;

/**
 * Modifies {@link Package} in order to maintain backwards compatibility.
 *
 * @hide
 */
@VisibleForTesting
public class PackageBackwardCompatibility {

    private static final String ANDROID_TEST_MOCK = "android.test.mock";

    private static final String ANDROID_TEST_RUNNER = "android.test.runner";

    private static final String APACHE_HTTP_LEGACY = "org.apache.http.legacy";

    /**
     * Modify the shared libraries in the supplied {@link Package} to maintain backwards
     * compatibility.
     *
     * @param pkg the {@link Package} to modify.
     */
    @VisibleForTesting
    public static void modifySharedLibraries(Package pkg) {
        ArrayList<String> usesLibraries = pkg.usesLibraries;
        ArrayList<String> usesOptionalLibraries = pkg.usesOptionalLibraries;

        // Packages targeted at <= O_MR1 expect the classes in the org.apache.http.legacy library
        // to be accessible so this maintains backward compatibility by adding the
        // org.apache.http.legacy library to those packages.
        if (apkTargetsApiLevelLessThanOrEqualToOMR1(pkg)) {
            boolean apacheHttpLegacyPresent = isLibraryPresent(
                    usesLibraries, usesOptionalLibraries, APACHE_HTTP_LEGACY);
            if (!apacheHttpLegacyPresent) {
                usesLibraries = prefix(usesLibraries, APACHE_HTTP_LEGACY);
            }
        }

        // android.test.runner has a dependency on android.test.mock so if android.test.runner
        // is present but android.test.mock is not then add android.test.mock.
        boolean androidTestMockPresent = isLibraryPresent(
                usesLibraries, usesOptionalLibraries, ANDROID_TEST_MOCK);
        if (ArrayUtils.contains(usesLibraries, ANDROID_TEST_RUNNER) && !androidTestMockPresent) {
            usesLibraries.add(ANDROID_TEST_MOCK);
        }
        if (ArrayUtils.contains(usesOptionalLibraries, ANDROID_TEST_RUNNER)
                && !androidTestMockPresent) {
            usesOptionalLibraries.add(ANDROID_TEST_MOCK);
        }

        pkg.usesLibraries = usesLibraries;
        pkg.usesOptionalLibraries = usesOptionalLibraries;
    }

    private static boolean apkTargetsApiLevelLessThanOrEqualToOMR1(Package pkg) {
        int targetSdkVersion = pkg.applicationInfo.targetSdkVersion;
        return targetSdkVersion <= Build.VERSION_CODES.O_MR1;
    }

    private static boolean isLibraryPresent(ArrayList<String> usesLibraries,
            ArrayList<String> usesOptionalLibraries, String apacheHttpLegacy) {
        return ArrayUtils.contains(usesLibraries, apacheHttpLegacy)
                || ArrayUtils.contains(usesOptionalLibraries, apacheHttpLegacy);
    }

    private static @NonNull <T> ArrayList<T> prefix(@Nullable ArrayList<T> cur, T val) {
        if (cur == null) {
            cur = new ArrayList<>();
        }
        cur.add(0, val);
        return cur;
    }
}
