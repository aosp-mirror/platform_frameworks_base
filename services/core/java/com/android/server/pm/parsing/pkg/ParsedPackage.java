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
import android.content.pm.PackageParser;

/**
 * Methods used for mutation after direct package parsing, mostly done inside
 * {@link com.android.server.pm.PackageManagerService}.
 *
 * Java disallows defining this as an inner interface, so this must be a separate file.
 *
 * @hide
 */
public interface ParsedPackage extends AndroidPackage {

    AndroidPackage hideAsFinal();

    ParsedPackage addUsesLibrary(int index, String libraryName);

    ParsedPackage addUsesOptionalLibrary(int index, String libraryName);

    ParsedPackage capPermissionPriorities();

    ParsedPackage clearAdoptPermissions();

    ParsedPackage clearOriginalPackages();

    ParsedPackage clearProtectedBroadcasts();

    ParsedPackage setBaseCodePath(String baseCodePath);

    ParsedPackage setCodePath(String codePath);

    ParsedPackage setNativeLibraryDir(String nativeLibraryDir);

    ParsedPackage setNativeLibraryRootDir(String nativeLibraryRootDir);

    ParsedPackage setPackageName(String packageName);

    ParsedPackage setPrimaryCpuAbi(String primaryCpuAbi);

    ParsedPackage setRealPackage(@Nullable String realPackage);

    ParsedPackage setSecondaryCpuAbi(String secondaryCpuAbi);

    ParsedPackage setSigningDetails(PackageParser.SigningDetails signingDetails);

    ParsedPackage setSplitCodePaths(String[] splitCodePaths);

    ParsedPackage setNativeLibraryRootRequiresIsa(boolean nativeLibraryRootRequiresIsa);

    ParsedPackage setAllComponentsDirectBootAware(boolean allComponentsDirectBootAware);

    ParsedPackage setFactoryTest(boolean factoryTest);

    ParsedPackage markNotActivitiesAsNotExportedIfSingleUser();

    ParsedPackage setOdm(boolean odm);

    ParsedPackage setOem(boolean oem);

    ParsedPackage setPrivileged(boolean privileged);

    ParsedPackage setProduct(boolean product);

    ParsedPackage setSignedWithPlatformKey(boolean signedWithPlatformKey);

    ParsedPackage setSystem(boolean system);

    ParsedPackage setSystemExt(boolean systemExt);

    ParsedPackage setVendor(boolean vendor);

    ParsedPackage removePermission(int index);

    ParsedPackage removeUsesLibrary(String libraryName);

    ParsedPackage removeUsesOptionalLibrary(String libraryName);

    ParsedPackage setCoreApp(boolean coreApp);

    ParsedPackage setStub(boolean isStub);

    ParsedPackage setRestrictUpdateHash(byte[] restrictUpdateHash);

    ParsedPackage setSeInfo(String seInfo);

    ParsedPackage setSeInfoUser(String seInfoUser);

    ParsedPackage setSecondaryNativeLibraryDir(String secondaryNativeLibraryDir);

    ParsedPackage setUid(int uid);

    ParsedPackage setVersionCode(int versionCode);

    ParsedPackage setVersionCodeMajor(int versionCodeMajor);

    // TODO(b/135203078): Move logic earlier in parse chain so nothing needs to be reverted
    ParsedPackage setDefaultToDeviceProtectedStorage(boolean defaultToDeviceProtectedStorage);

    ParsedPackage setDirectBootAware(boolean directBootAware);

    ParsedPackage setPersistent(boolean persistent);
}
