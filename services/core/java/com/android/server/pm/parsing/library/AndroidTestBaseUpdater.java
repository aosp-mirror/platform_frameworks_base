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

import static com.android.server.pm.parsing.library.SharedLibraryNames.ANDROID_TEST_BASE;
import static com.android.server.pm.parsing.library.SharedLibraryNames.ANDROID_TEST_RUNNER;

import android.annotation.NonNull;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.content.Context;
import android.os.Build;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.compat.IPlatformCompat;
import com.android.internal.pm.parsing.pkg.ParsedPackage;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.pm.pkg.AndroidPackage;

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
    private static final String TAG = "AndroidTestBaseUpdater";

    /**
     * Remove android.test.base library for apps that target SDK R or more and do not depend on
     * android.test.runner (as it depends on classes from the android.test.base library).
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.Q)
    private static final long REMOVE_ANDROID_TEST_BASE = 133396946L;

    private static boolean isChangeEnabled(@NonNull AndroidPackage pkg, boolean isSystemApp) {
        // Do not ask platform compat for system apps to prevent a boot time regression in tests.
        // b/142558883.
        if (!isSystemApp) {
            IPlatformCompat platformCompat = IPlatformCompat.Stub.asInterface(
                    ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE));
            try {
                return platformCompat.isChangeEnabled(REMOVE_ANDROID_TEST_BASE,
                        AndroidPackageUtils.generateAppInfoWithoutState(pkg));
            } catch (RemoteException | NullPointerException e) {
                Log.e(TAG, "Failed to get a response from PLATFORM_COMPAT_SERVICE", e);
            }
        }
        // Fall back to previous behaviour.
        return pkg.getTargetSdkVersion() > Build.VERSION_CODES.Q;
    }

    @Override
    public void updatePackage(ParsedPackage pkg, boolean isSystemApp,
            boolean isUpdatedSystemApp) {
        // Packages targeted at <= Q expect the classes in the android.test.base library
        // to be accessible so this maintains backward compatibility by adding the
        // android.test.base library to those packages.
        if (!isChangeEnabled(pkg, isSystemApp)) {
            prefixRequiredLibrary(pkg, ANDROID_TEST_BASE);
        } else {
            // If a package already depends on android.test.runner then add a dependency on
            // android.test.base because android.test.runner depends on classes from the
            // android.test.base library.
            prefixImplicitDependency(pkg, ANDROID_TEST_RUNNER, ANDROID_TEST_BASE);
        }
    }
}
