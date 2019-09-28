/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.content.pm.parsing;

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

    /**
     * TODO(b/135203078): Use non-AppInfo method
     * @deprecated use {@link #setCodePath(String)}
     */
    @Deprecated
    ParsedPackage setApplicationInfoCodePath(String applicationInfoCodePath);

    /**
     * TODO(b/135203078): Use non-AppInfo method
     * @deprecated use {@link #setCodePath(String)}
     */
    @Deprecated
    ParsedPackage setApplicationInfoResourcePath(String applicationInfoResourcePath);

    ParsedPackage setBaseCodePath(String baseCodePath);

    ParsedPackage setCodePath(String codePath);

    ParsedPackage setCpuAbiOverride(String cpuAbiOverride);

    ParsedPackage setNativeLibraryDir(String nativeLibraryDir);

    ParsedPackage setNativeLibraryRootDir(String nativeLibraryRootDir);

    ParsedPackage setPackageName(String packageName);

    ParsedPackage setPrimaryCpuAbi(String primaryCpuAbi);

    ParsedPackage setProcessName(String processName);

    ParsedPackage setRealPackage(String realPackage);

    ParsedPackage setSecondaryCpuAbi(String secondaryCpuAbi);

    ParsedPackage setSigningDetails(PackageParser.SigningDetails signingDetails);

    ParsedPackage setSplitCodePaths(String[] splitCodePaths);

    ParsedPackage initForUser(int userId);

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

    ParsedPackage setUpdatedSystemApp(boolean updatedSystemApp);

    ParsedPackage setVendor(boolean vendor);

    ParsedPackage removePermission(int index);

    ParsedPackage removeUsesLibrary(String libraryName);

    ParsedPackage removeUsesOptionalLibrary(String libraryName);

    ParsedPackage setApplicationInfoBaseResourcePath(String applicationInfoBaseResourcePath);

    ParsedPackage setApplicationInfoSplitResourcePaths(
            String[] applicationInfoSplitResourcePaths);

    ParsedPackage setApplicationVolumeUuid(String applicationVolumeUuid);

    ParsedPackage setCoreApp(boolean coreApp);

    ParsedPackage setIsStub(boolean isStub);

    // TODO(b/135203078): Remove entirely
    ParsedPackage setPackageSettingCallback(PackageSettingCallback packageSettingCallback);

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

    interface PackageSettingCallback {
        default void setAndroidPackage(AndroidPackage pkg){}
    }
}
