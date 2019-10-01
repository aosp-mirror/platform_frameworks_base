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

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageParser;

import com.android.internal.util.ArrayUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

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

    public PackageBuilder setApplicationInfoTargetSdkVersion(int versionCode) {
        mPkg.applicationInfo.targetSdkVersion = versionCode;
        return this;
    }

    public PackageBuilder setQueriesIntents(Collection<Intent> queriesIntents) {
        mPkg.mQueriesIntents = new ArrayList<>(queriesIntents);
        return this;
    }

    public PackageBuilder setQueriesIntents(Intent... intents) {
        return setQueriesIntents(Arrays.asList(intents));
    }

    public PackageBuilder setQueriesPackages(Collection<String> queriesPackages) {
        mPkg.mQueriesPackages = new ArrayList<>(queriesPackages);
        return this;
    }

    public PackageBuilder setQueriesPackages(String... queriesPackages) {
        return setQueriesPackages(Arrays.asList(queriesPackages));
    }

    public PackageBuilder setForceQueryable(boolean forceQueryable) {
        mPkg.mForceQueryable = forceQueryable;
        return this;
    }

    public interface ParseComponentArgsCreator {
        PackageParser.ParseComponentArgs create(PackageParser.Package pkg);
    }

    public PackageBuilder addActivity(ParseComponentArgsCreator argsCreator, ActivityInfo info) {
        mPkg.activities.add(new PackageParser.Activity(argsCreator.create(mPkg), info));
        return this;
    }

    public interface ActivityIntentInfoCreator {
        PackageParser.ActivityIntentInfo create(PackageParser.Activity activity);
    }

    public PackageBuilder addActivityIntentInfo(
            int activityIndex, ActivityIntentInfoCreator creator) {
        final PackageParser.Activity activity = mPkg.activities.get(activityIndex);
        activity.intents.add(creator.create(activity));
        return this;
    }
}
