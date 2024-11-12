/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.pm;

import static android.content.pm.PackageManager.INSTALL_FAILED_MISSING_SHARED_LIBRARY;

import android.content.pm.SharedLibraryInfo;
import android.content.pm.parsing.PackageLite;
import android.os.OutcomeReceiver;

import java.util.List;

/**
 * Helper class to interact with SDK Dependency Installer service.
 */
public class InstallDependencyHelper {
    private final SharedLibrariesImpl mSharedLibraries;

    InstallDependencyHelper(SharedLibrariesImpl sharedLibraries) {
        mSharedLibraries = sharedLibraries;
    }

    void resolveLibraryDependenciesIfNeeded(PackageLite pkg,
            OutcomeReceiver<Void, PackageManagerException> callback) {
        final List<SharedLibraryInfo> missing;
        try {
            missing = mSharedLibraries.collectMissingSharedLibraryInfos(pkg);
        } catch (PackageManagerException e) {
            callback.onError(e);
            return;
        }

        if (missing.isEmpty()) {
            // No need for dependency resolution. Move to installation directly.
            callback.onResult(null);
            return;
        }

        try {
            bindToDependencyInstaller();
        } catch (Exception e) {
            PackageManagerException pe = new PackageManagerException(
                    INSTALL_FAILED_MISSING_SHARED_LIBRARY, e.getMessage());
            callback.onError(pe);
        }
    }

    private void bindToDependencyInstaller() {
        throw new IllegalStateException("Failed to bind to Dependency Installer");
    }


}
