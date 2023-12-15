/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.pm.parsing;

import android.annotation.NonNull;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.content.res.TypedArray;
import android.permission.PermissionManager;

import com.android.internal.pm.parsing.pkg.PackageImpl;
import com.android.internal.pm.parsing.pkg.ParsedPackage;
import com.android.internal.pm.pkg.parsing.ParsingPackage;
import com.android.internal.pm.pkg.parsing.ParsingPackageUtils;
import com.android.server.SystemConfig;

import java.io.File;
import java.util.List;
import java.util.Set;

/** @hide **/
public class ParsingUtils {

    /**
     * @see ParsingPackageUtils#parseDefault(ParseInput, File, int, List, boolean,
     * ParsingPackageUtils.Callback)
     */
    @NonNull
    public static ParseResult<ParsedPackage> parseDefaultOneTime(File file,
            @ParsingPackageUtils.ParseFlags int parseFlags,
            @NonNull List<PermissionManager.SplitPermissionInfo> splitPermissions,
            boolean collectCertificates) {
        ParseInput input = ParseTypeImpl.forDefaultParsing().reset();
        return ParsingPackageUtils.parseDefault(input, file, parseFlags, splitPermissions, collectCertificates,
                new ParsingPackageUtils.Callback() {
            @Override
            public boolean hasFeature(String feature) {
                // Assume the device doesn't support anything. This will affect permission
                // parsing and will force <uses-permission/> declarations to include all
                // requiredNotFeature permissions and exclude all requiredFeature
                // permissions. This mirrors the old behavior.
                return false;
            }

            @Override
            public ParsingPackage startParsingPackage(
                    @NonNull String packageName,
                    @NonNull String baseApkPath,
                    @NonNull String path,
                    @NonNull TypedArray manifestArray, boolean isCoreApp) {
                return PackageImpl.forParsing(packageName, baseApkPath, path, manifestArray,
                        isCoreApp, this);
            }

            @Override
            public Set<String> getHiddenApiWhitelistedApps() {
                return SystemConfig.getInstance().getHiddenApiWhitelistedApps();
            }

            @Override
            public Set<String> getInstallConstraintsAllowlist() {
                return SystemConfig.getInstance().getInstallConstraintsAllowlist();
            }
        });
    }
}
