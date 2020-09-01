/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.om;


import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.om.OverlayableInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import com.android.server.pm.PackageManagerServiceUtils;

import java.io.IOException;
import java.util.Map;

/**
 * Delegate to the system for querying information about overlayables and packages.
 */
public interface OverlayableInfoCallback {

    /**
     * Read from the APK and AndroidManifest of a package to return the overlayable defined for
     * a given name.
     *
     * @throws IOException if the target can't be read
     */
    @Nullable
    OverlayableInfo getOverlayableForTarget(@NonNull String packageName,
            @NonNull String targetOverlayableName, int userId)
            throws IOException;

    /**
     * @see PackageManager#getPackagesForUid(int)
     */
    @Nullable
    String[] getPackagesForUid(int uid);

    /**
     * @param userId user to filter package visibility by
     * @see PackageManager#getPackageInfo(String, int)
     */
    @Nullable
    PackageInfo getPackageInfo(@NonNull String packageName, int userId);

    /**
     * @return map of system pre-defined, uniquely named actors; keys are namespace,
     * value maps actor name to package name
     */
    @NonNull
    Map<String, Map<String, String>> getNamedActors();

    /**
     * @return true if the target package has declared an overlayable
     */
    boolean doesTargetDefineOverlayable(String targetPackageName, int userId) throws IOException;

    /**
     * @throws SecurityException containing message if the caller doesn't have the given
     *                           permission
     */
    void enforcePermission(String permission, String message) throws SecurityException;

    /**
     * @return true if {@link PackageManagerServiceUtils#compareSignatures} run on both packages
     *     in the system returns {@link PackageManager#SIGNATURE_MATCH}
     */
    boolean signaturesMatching(@NonNull String pkgName1, @NonNull String pkgName2, int userId);
}
