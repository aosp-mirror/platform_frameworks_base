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

import static org.junit.Assert.assertEquals;

import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.ParsedPackage;

import java.util.function.Supplier;

/**
 * Helper for classes that test {@link PackageSharedLibraryUpdater}.
 */
abstract class PackageSharedLibraryUpdaterTest {

    protected static final String PACKAGE_NAME = "org.package.name";

    static void checkBackwardsCompatibility(ParsedPackage before, AndroidPackage after,
            Supplier<PackageSharedLibraryUpdater> updaterSupplier) {
        updaterSupplier.get().updatePackage(before, false);
        check(before.hideAsFinal(), after);
    }

    private static void check(AndroidPackage before, AndroidPackage after) {
        assertEquals("targetSdkVersion should not be changed",
                after.getTargetSdkVersion(),
                before.getTargetSdkVersion());
        assertEquals("usesLibraries not updated correctly",
                after.getUsesLibraries(),
                before.getUsesLibraries());
        assertEquals("usesOptionalLibraries not updated correctly",
                after.getUsesOptionalLibraries(),
                before.getUsesOptionalLibraries());
    }
}
