/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.util.ArrayMap;

import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.build.UnboundedSdkLevel;
import com.android.server.SystemConfig;
import com.android.server.pm.parsing.pkg.ParsedPackage;

/**
 * Updates packages to add or remove dependencies on shared libraries as per attributes
 * in the library declaration
 *
 * @hide
 */
@VisibleForTesting
public class ApexSharedLibraryUpdater extends PackageSharedLibraryUpdater {

    /**
     * ArrayMap like the one you find in {@link SystemConfig}. The keys are the library names.
     */
    private final ArrayMap<String, SystemConfig.SharedLibraryEntry> mSharedLibraries;

    public ApexSharedLibraryUpdater(
            ArrayMap<String, SystemConfig.SharedLibraryEntry> sharedLibraries) {
        mSharedLibraries = sharedLibraries;
    }

    @Override
    public void updatePackage(ParsedPackage parsedPackage, boolean isUpdatedSystemApp) {
        final int builtInLibCount = mSharedLibraries.size();
        for (int i = 0; i < builtInLibCount; i++) {
            updateSharedLibraryForPackage(mSharedLibraries.valueAt(i), parsedPackage);
        }
    }

    private void updateSharedLibraryForPackage(SystemConfig.SharedLibraryEntry entry,
            ParsedPackage parsedPackage) {
        if (entry.onBootclasspathBefore != null
                && isTargetSdkAtMost(
                        parsedPackage.getTargetSdkVersion(),
                        entry.onBootclasspathBefore)
                && UnboundedSdkLevel.isAtLeast(entry.onBootclasspathBefore)) {
            // this package targets an API where this library was in the BCP, so add
            // the library transparently in case the package is using it
            prefixRequiredLibrary(parsedPackage, entry.name);
        }

        if (entry.canBeSafelyIgnored) {
            // the library is now present in the BCP and always available; we don't need to add
            // it a second time
            removeLibrary(parsedPackage, entry.name);
        }
    }

    private static boolean isTargetSdkAtMost(int targetSdk, String onBcpBefore) {
        if (isCodename(onBcpBefore)) {
            return targetSdk < 10000;
        }
        return targetSdk < Integer.parseInt(onBcpBefore);
    }

    private static boolean isCodename(String version) {
        if (version.length() == 0) {
            throw new IllegalArgumentException();
        }
        // assume Android codenames start with upper case letters.
        return Character.isUpperCase((version.charAt(0)));
    }
}
