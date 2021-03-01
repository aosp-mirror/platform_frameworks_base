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

package com.android.server.pm.verify.domain;

import android.annotation.CheckResult;
import android.annotation.NonNull;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.os.Binder;

import com.android.internal.util.CollectionUtils;
import com.android.server.compat.PlatformCompat;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.parsing.pkg.AndroidPackage;

import java.util.List;
import java.util.Set;

public final class DomainVerificationUtils {

    /**
     * Consolidates package exception messages. A generic unavailable message is included since the
     * caller doesn't bother to check why the package isn't available.
     */
    @CheckResult
    static NameNotFoundException throwPackageUnavailable(@NonNull String packageName)
            throws NameNotFoundException {
        throw new NameNotFoundException("Package " + packageName + " unavailable");
    }

    public static boolean isDomainVerificationIntent(Intent intent,
            @NonNull List<ResolveInfo> candidates,
            @PackageManager.ResolveInfoFlags int resolveInfoFlags) {
        if (!intent.isWebIntent()) {
            return false;
        }

        Set<String> categories = intent.getCategories();
        int categoriesSize = CollectionUtils.size(categories);
        if (categoriesSize > 2) {
            // Specifying at least one non-app-link category
            return false;
        } else if (categoriesSize == 2) {
            // Check for explicit app link intent with exactly BROWSABLE && DEFAULT
            return intent.hasCategory(Intent.CATEGORY_DEFAULT)
                    && intent.hasCategory(Intent.CATEGORY_BROWSABLE);
        }

            // In cases where at least one browser is resolved and only one non-browser is resolved,
        // the Intent is coerced into an app links intent, under the assumption the browser can
        // be skipped if the app is approved at any level for the domain.
        boolean foundBrowser = false;
        boolean foundOneApp = false;

        final int candidatesSize = candidates.size();
        for (int index = 0; index < candidatesSize; index++) {
            final ResolveInfo info = candidates.get(index);
            if (info.handleAllWebDataURI) {
                foundBrowser = true;
            } else if (foundOneApp) {
                // Already true, so duplicate app
                foundOneApp = false;
                break;
            } else {
                foundOneApp = true;
            }
        }

        boolean matchDefaultByFlags = (resolveInfoFlags & PackageManager.MATCH_DEFAULT_ONLY) != 0;
        boolean onlyOneNonBrowser = foundBrowser && foundOneApp;

        // Check if matches (BROWSABLE || none) && DEFAULT
        if (categoriesSize == 0) {
            // No categories, run coerce case, matching DEFAULT by flags
            return onlyOneNonBrowser && matchDefaultByFlags;
        } else if (intent.hasCategory(Intent.CATEGORY_DEFAULT)) {
            // Run coerce case, matching by explicit DEFAULT
            return onlyOneNonBrowser;
        } else if (intent.hasCategory(Intent.CATEGORY_BROWSABLE)) {
            // Intent matches BROWSABLE, must match DEFAULT by flags
            return matchDefaultByFlags;
        } else {
            // Otherwise not matching any app link categories
            return false;
        }
    }

    static boolean isChangeEnabled(PlatformCompat platformCompat, AndroidPackage pkg,
            long changeId) {
        //noinspection ConstantConditions
        return Binder.withCleanCallingIdentity(
                () -> platformCompat.isChangeEnabled(changeId, buildMockAppInfo(pkg)));
    }

    /**
     * Passed to {@link PlatformCompat} because this can be invoked mid-install process or when
     * {@link PackageManagerService#mLock} is being held, and {@link PlatformCompat} will not be
     * able to query the pending {@link ApplicationInfo} from {@link PackageManager}.
     * <p>
     * TODO(b/177613575): Can a different API be used?
     */
    @NonNull
    private static ApplicationInfo buildMockAppInfo(@NonNull AndroidPackage pkg) {
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.packageName = pkg.getPackageName();
        appInfo.targetSdkVersion = pkg.getTargetSdkVersion();
        return appInfo;
    }
}
