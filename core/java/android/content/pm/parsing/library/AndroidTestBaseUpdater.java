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
import static android.content.pm.parsing.library.SharedLibraryNames.ANDROID_TEST_RUNNER;

import android.content.pm.parsing.AndroidPackage;
import android.content.pm.parsing.ParsedPackage;
import android.os.Build;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Updates a package to ensure that if it targets <= Q that the android.test.base library is
 * included by default.
 *
 * <p>This is separated out so that it can be conditionally included at build time depending on
 * whether android.test.base is on the bootclasspath or not. In order to include this at
 * build time, and remove android.test.base from the bootclasspath pass
 * REMOVE_ATB_FROM_BCP=true on the build command line, otherwise this class will not be included
 * and the
 *
 * @hide
 */
@VisibleForTesting
public class AndroidTestBaseUpdater extends PackageSharedLibraryUpdater {

    private static boolean apkTargetsApiLevelLessThanOrEqualToQ(AndroidPackage pkg) {
        return pkg.getTargetSdkVersion() <= Build.VERSION_CODES.Q;
    }

    @Override
    public void updatePackage(ParsedPackage parsedPackage) {
        // Packages targeted at <= Q expect the classes in the android.test.base library
        // to be accessible so this maintains backward compatibility by adding the
        // android.test.base library to those packages.
        if (apkTargetsApiLevelLessThanOrEqualToQ(parsedPackage)) {
            prefixRequiredLibrary(parsedPackage, ANDROID_TEST_BASE);
        } else {
            // If a package already depends on android.test.runner then add a dependency on
            // android.test.base because android.test.runner depends on classes from the
            // android.test.base library.
            prefixImplicitDependency(parsedPackage, ANDROID_TEST_RUNNER, ANDROID_TEST_BASE);
        }
    }
}
