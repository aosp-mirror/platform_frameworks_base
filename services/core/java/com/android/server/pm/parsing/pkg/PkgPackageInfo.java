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

package com.android.server.pm.parsing.pkg;

import android.content.pm.PackageInfo;
import android.content.pm.parsing.PkgWithoutStatePackageInfo;

import com.android.server.pm.PackageManagerService;

/**
 * Equivalent of {@link PkgWithoutStatePackageInfo} but contains fields that are set inside {@link
 * PackageManagerService} and thus are not available to the core SDK.
 * <p>
 * Everything in this interface is @SystemApi(client = SYSTEM_SERVER), but the interface itself is
 * not.
 */
public interface PkgPackageInfo extends PkgWithoutStatePackageInfo {

    /**
     * For marking packages required for a minimal boot state, through the "coreApp" manifest
     * attribute.
     *
     * @see PackageInfo#coreApp
     */
    boolean isCoreApp();

    /**
     * Whether or not the package is a stub and must be replaced by the full version.
     *
     * @see PackageInfo#isStub
     */
    boolean isStub();
}
