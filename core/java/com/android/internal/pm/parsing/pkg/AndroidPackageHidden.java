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

package com.android.internal.pm.parsing.pkg;

import android.annotation.Nullable;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;

/**
 * Methods that normal consumers should not have access to. This usually means the field is stateful
 * or deprecated and should be access through
 * {@link com.android.server.pm.parsing.pkg.AndroidPackageUtils} or a system manager class.
 * <p>
 * This is a separate interface, not implemented by the base {@link AndroidPackage} because Java
 * doesn't support non-public interface methods. The class must be cast to this interface.
 * <p>
 * Because they exist in different packages, some methods are duplicated from
 * android.content.pm.parsing.ParsingPackageHidden.
 * @hide
 */
// TODO: remove public after moved PackageImpl and AndroidPackageUtils
public interface AndroidPackageHidden {

    /**
     * @see ApplicationInfo#primaryCpuAbi
     */
    @Nullable
    String getPrimaryCpuAbi();

    /**
     * @see ApplicationInfo#secondaryCpuAbi
     */
    @Nullable
    String getSecondaryCpuAbi();

    /**
     * @see PackageInfo#versionCode
     * @see ApplicationInfo#versionCode
     */
    @Deprecated
    int getVersionCode();

    /**
     * @see PackageInfo#versionCodeMajor
     */
    int getVersionCodeMajor();

    // TODO(b/135203078): Hide and enforce going through PackageInfoUtils
    ApplicationInfo toAppInfoWithoutState();

    // TODO: Remove these booleans and store the value directly inside PackageState
    boolean isSystem();

    boolean isSystemExt();

    boolean isPrivileged();

    boolean isOem();

    boolean isVendor();

    boolean isProduct();

    boolean isOdm();
}
