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

import android.annotation.Nullable;
import android.content.pm.ApplicationInfo;
import com.android.server.pm.pkg.parsing.PkgWithoutStateAppInfo;

import com.android.server.pm.PackageManagerService;
import com.android.server.pm.pkg.PackageState;

/**
 * Equivalent of {@link PkgWithoutStateAppInfo} but contains fields that are set inside {@link
 * PackageManagerService} and thus are not available to the core SDK.
 * <p>
 * Everything in this interface is @SystemApi(client = SYSTEM_SERVER), but the interface itself is
 * not.
 */
public interface PkgAppInfo extends PkgWithoutStateAppInfo {

    /**
     * @see ApplicationInfo#nativeLibraryDir
     */
    @Nullable
    String getNativeLibraryDir();

    /**
     * @see ApplicationInfo#nativeLibraryRootDir
     */
    @Nullable
    String getNativeLibraryRootDir();

    /**
     * @see ApplicationInfo#secondaryNativeLibraryDir
     */
    @Nullable
    String getSecondaryNativeLibraryDir();

    /**
     * This is an appId, the {@link ApplicationInfo#uid} if the user ID is
     * {@link android.os.UserHandle#SYSTEM}.
     *
     * @deprecated Use {@link PackageState#getAppId()} instead.
     */
    @Deprecated
    int getUid();

    /**
     * @see ApplicationInfo#FLAG_FACTORY_TEST
     */
    boolean isFactoryTest();

    /**
     * @see ApplicationInfo#nativeLibraryRootRequiresIsa
     */
    boolean isNativeLibraryRootRequiresIsa();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_ODM
     */
    boolean isOdm();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_OEM
     */
    boolean isOem();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_PRIVILEGED
     */
    boolean isPrivileged();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_PRODUCT
     */
    boolean isProduct();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_SIGNED_WITH_PLATFORM_KEY
     */
    boolean isSignedWithPlatformKey();

    /**
     * @see ApplicationInfo#FLAG_SYSTEM
     */
    boolean isSystem();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_SYSTEM_EXT
     */
    boolean isSystemExt();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_VENDOR
     */
    boolean isVendor();
}
