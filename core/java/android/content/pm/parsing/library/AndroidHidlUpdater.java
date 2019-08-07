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

import static android.content.pm.parsing.library.SharedLibraryNames.ANDROID_HIDL_BASE;
import static android.content.pm.parsing.library.SharedLibraryNames.ANDROID_HIDL_MANAGER;

import android.content.pm.parsing.ParsedPackage;
import android.os.Build;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Updates a package to ensure that if it targets <= P that the android.hidl.base-V1.0-java
 * and android.hidl.manager-V1.0-java libraries are included by default.
 *
 * @hide
 */
@VisibleForTesting
public class AndroidHidlUpdater extends PackageSharedLibraryUpdater {

    @Override
    public void updatePackage(ParsedPackage parsedPackage) {
        // This was the default <= P and is maintained for backwards compatibility.
        boolean isLegacy = parsedPackage.getTargetSdkVersion() <= Build.VERSION_CODES.P;
        // Only system apps use these libraries
        boolean isSystem = parsedPackage.isSystemApp() || parsedPackage.isUpdatedSystemApp();

        if (isLegacy && isSystem) {
            prefixRequiredLibrary(parsedPackage, ANDROID_HIDL_BASE);
            prefixRequiredLibrary(parsedPackage, ANDROID_HIDL_MANAGER);
        } else {
            removeLibrary(parsedPackage, ANDROID_HIDL_BASE);
            removeLibrary(parsedPackage, ANDROID_HIDL_MANAGER);
        }
    }
}
