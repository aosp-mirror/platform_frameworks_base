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

import static android.content.pm.SharedLibraryNames.ANDROID_HIDL_BASE;
import static android.content.pm.SharedLibraryNames.ANDROID_HIDL_MANAGER;

import android.content.pm.PackageParser.Package;
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
    public void updatePackage(Package pkg) {
        ApplicationInfo info = pkg.applicationInfo;

        // This was the default <= P and is maintained for backwards compatibility.
        boolean isLegacy = info.targetSdkVersion <= Build.VERSION_CODES.P;
        // Only system apps use these libraries
        boolean isSystem = info.isSystemApp() || info.isUpdatedSystemApp();

        if (isLegacy && isSystem) {
            prefixRequiredLibrary(pkg, ANDROID_HIDL_BASE);
            prefixRequiredLibrary(pkg, ANDROID_HIDL_MANAGER);
        } else {
            removeLibrary(pkg, ANDROID_HIDL_BASE);
            removeLibrary(pkg, ANDROID_HIDL_MANAGER);
        }
    }
}
