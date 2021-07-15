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

package com.android.server.pm.dex;

import static com.android.server.pm.InstructionSets.getAppDexInstructionSets;

import android.annotation.NonNull;

import com.android.server.pm.PackageDexOptimizer;
import com.android.server.pm.PackageSetting;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;

import java.io.File;
import java.util.Arrays;

/**
 * Utility class to interface between PM and ART tooling (e.g. DexManager).
 */
public final class ArtUtils {
    private ArtUtils() {
    }

    /**
     * Create the ART-representation of package info.
     */
    public static ArtPackageInfo createArtPackageInfo(
            AndroidPackage pkg, PackageSetting pkgSetting) {
        return new ArtPackageInfo(
                pkg.getPackageName(),
                Arrays.asList(getAppDexInstructionSets(
                        AndroidPackageUtils.getPrimaryCpuAbi(pkg, pkgSetting),
                        AndroidPackageUtils.getSecondaryCpuAbi(pkg, pkgSetting))),
                AndroidPackageUtils.getAllCodePaths(pkg),
                getOatDir(pkg, pkgSetting));
    }

    private static String getOatDir(AndroidPackage pkg, @NonNull PackageSetting pkgSetting) {
        if (!AndroidPackageUtils.canHaveOatDir(pkg,
                pkgSetting.getPkgState().isUpdatedSystemApp())) {
            return null;
        }
        File codePath = new File(pkg.getPath());
        if (codePath.isDirectory()) {
            return PackageDexOptimizer.getOatDir(codePath).getAbsolutePath();
        }
        return null;
    }

}
