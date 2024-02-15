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
package com.android.server.pm.parsing.library;

import static com.android.server.pm.parsing.library.SharedLibraryNames.ORG_APACHE_HTTP_LEGACY;

import android.os.Build;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.pm.parsing.pkg.ParsedPackage;
import com.android.server.pm.pkg.AndroidPackage;

/**
 * Updates a package to ensure that if it targets < P that the org.apache.http.legacy library is
 * included by default.
 *
 * @hide
 */
@VisibleForTesting
public class OrgApacheHttpLegacyUpdater extends PackageSharedLibraryUpdater {

    private static boolean apkTargetsApiLevelLessThanOrEqualToOMR1(AndroidPackage pkg) {
        return pkg.getTargetSdkVersion() < Build.VERSION_CODES.P;
    }

    @Override
    public void updatePackage(ParsedPackage parsedPackage, boolean isSystemApp,
            boolean isUpdatedSystemApp) {
        // Packages targeted at <= O_MR1 expect the classes in the org.apache.http.legacy library
        // to be accessible so this maintains backward compatibility by adding the
        // org.apache.http.legacy library to those packages.
        if (apkTargetsApiLevelLessThanOrEqualToOMR1(parsedPackage)) {
            prefixRequiredLibrary(parsedPackage, ORG_APACHE_HTTP_LEGACY);
        }
    }
}
