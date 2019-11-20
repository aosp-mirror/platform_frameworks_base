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

package com.android.server.pm;

import android.content.pm.PackageParser;

import com.android.internal.util.ArrayUtils;

class PackageBuilder {
    final PackageParser.Package mPkg;

    PackageBuilder(String packageName) {
        mPkg = new PackageParser.Package(packageName);
    }

    PackageBuilder setApplicationInfoCodePath(String codePath) {
        mPkg.applicationInfo.setCodePath(codePath);
        return this;
    }

    PackageBuilder setApplicationInfoResourcePath(String resourcePath) {
        mPkg.applicationInfo.setResourcePath(resourcePath);
        return this;
    }

    PackageBuilder setCodePath(String codePath) {
        mPkg.codePath = codePath;
        return this;
    }

    PackageBuilder setBaseCodePath(String baseCodePath) {
        mPkg.baseCodePath = baseCodePath;
        return this;
    }

    PackageBuilder addUsesStaticLibrary(String name, long version) {
        mPkg.usesStaticLibraries = ArrayUtils.add(mPkg.usesStaticLibraries, name);
        mPkg.usesStaticLibrariesVersions =
                ArrayUtils.appendLong(mPkg.usesStaticLibrariesVersions, version);
        return this;
    }

    PackageBuilder setApplicationInfoNativeLibraryRootDir(String dir) {
        mPkg.applicationInfo.nativeLibraryRootDir = dir;
        return this;
    }

    PackageBuilder setStaticSharedLib(String staticSharedLibName, long staticSharedLibVersion) {
        mPkg.staticSharedLibVersion = staticSharedLibVersion;
        mPkg.staticSharedLibName = staticSharedLibName;
        return this;
    }

    PackageBuilder setManifestPackageName(String manifestPackageName) {
        mPkg.manifestPackageName = manifestPackageName;
        return this;
    }

    PackageBuilder setVersionCodeMajor(int versionCodeMajor) {
        mPkg.mVersionCodeMajor = versionCodeMajor;
        return this;
    }

    PackageBuilder setVersionCode(int versionCode) {
        mPkg.mVersionCode = versionCode;
        return this;
    }

    PackageBuilder addSplitCodePath(String splitCodePath) {
        mPkg.splitCodePaths =
                ArrayUtils.appendElement(String.class, mPkg.splitCodePaths, splitCodePath);
        return this;
    }

    PackageBuilder setApplicationInfoVolumeUuid(String volumeUuid) {
        mPkg.applicationInfo.volumeUuid = volumeUuid;
        return this;
    }

    PackageBuilder addLibraryName(String libraryName) {
        mPkg.libraryNames = ArrayUtils.add(mPkg.libraryNames, libraryName);
        return this;
    }

    PackageBuilder setRealPackageName(String realPackageName) {
        mPkg.mRealPackage = realPackageName;
        return this;
    }

    PackageBuilder setCpuAbiOVerride(String cpuAbiOverride) {
        mPkg.cpuAbiOverride = cpuAbiOverride;
        return this;
    }

    PackageBuilder addPermissionRequest(String permissionName) {
        mPkg.requestedPermissions.add(permissionName);
        return this;
    }

    PackageParser.Package build() {
        return mPkg;
    }

    public PackageBuilder addApplicationInfoFlag(int flag) {
        mPkg.applicationInfo.flags |= flag;
        return this;
    }
}
