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

import android.annotation.Nullable;
import android.content.pm.PackageParser.Package;

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

        usesLibraries = orgApacheHttpLegacy(usesLibraries);
        usesOptionalLibraries = orgApacheHttpLegacy(usesOptionalLibraries);

        // android.test.runner has a dependency on android.test.mock so if android.test.runner
        // is present but android.test.mock is not then add android.test.mock.
        boolean androidTestMockPresent = ArrayUtils.contains(usesLibraries, ANDROID_TEST_MOCK)
                || ArrayUtils.contains(usesOptionalLibraries, ANDROID_TEST_MOCK);
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

    private static ArrayList<String> orgApacheHttpLegacy(@Nullable ArrayList<String> libraries) {
        // "org.apache.http.legacy" is now a part of the boot classpath so it doesn't need
        // to be an explicit dependency.
        //
        // A future change will remove this library from the boot classpath, at which point
        // all apps that target SDK 21 and earlier will have it automatically added to their
        // dependency lists.
        return ArrayUtils.remove(libraries, "org.apache.http.legacy");
    }
}
