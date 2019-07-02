/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.UserInfo;
import android.service.pm.PackageProto;
import android.util.proto.ProtoOutputStream;

import com.android.server.pm.permission.PermissionsState;

import java.io.File;
import java.util.List;

/**
 * Settings data for a particular package we know about.
 */
public final class PackageSetting extends PackageSettingBase {
    int appId;
    PackageParser.Package pkg;
    /**
     * WARNING. The object reference is important. We perform integer equality and NOT
     * object equality to check whether shared user settings are the same.
     */
    SharedUserSetting sharedUser;
    /**
     * Temporary holding space for the shared user ID. While parsing package settings, the
     * shared users tag may come after the packages. In this case, we must delay linking the
     * shared user setting with the package setting. The shared user ID lets us link the
     * two objects.
     */
    private int sharedUserId;

    PackageSetting(String name, String realName, File codePath, File resourcePath,
            String legacyNativeLibraryPathString, String primaryCpuAbiString,
            String secondaryCpuAbiString, String cpuAbiOverrideString,
            long pVersionCode, int pkgFlags, int privateFlags, String parentPackageName,
            List<String> childPackageNames, int sharedUserId, String[] usesStaticLibraries,
            long[] usesStaticLibrariesVersions) {
        super(name, realName, codePath, resourcePath, legacyNativeLibraryPathString,
                primaryCpuAbiString, secondaryCpuAbiString, cpuAbiOverrideString,
                pVersionCode, pkgFlags, privateFlags, parentPackageName, childPackageNames,
                usesStaticLibraries, usesStaticLibrariesVersions);
        this.sharedUserId = sharedUserId;
    }

    /**
     * New instance of PackageSetting replicating the original settings.
     * Note that it keeps the same PackageParser.Package instance.
     */
    PackageSetting(PackageSetting orig) {
        super(orig, orig.realName);
        doCopy(orig);
    }

    /**
     * New instance of PackageSetting replicating the original settings, but, allows specifying
     * a real package name.
     * Note that it keeps the same PackageParser.Package instance.
     */
    PackageSetting(PackageSetting orig, String realPkgName) {
        super(orig, realPkgName);
        doCopy(orig);
    }

    public int getSharedUserId() {
        if (sharedUser != null) {
            return sharedUser.userId;
        }
        return sharedUserId;
    }

    public SharedUserSetting getSharedUser() {
        return sharedUser;
    }

    @Override
    public String toString() {
        return "PackageSetting{"
            + Integer.toHexString(System.identityHashCode(this))
            + " " + name + "/" + appId + "}";
    }

    public void copyFrom(PackageSetting orig) {
        super.copyFrom(orig);
        doCopy(orig);
    }

    private void doCopy(PackageSetting orig) {
        appId = orig.appId;
        pkg = orig.pkg;
        sharedUser = orig.sharedUser;
        sharedUserId = orig.sharedUserId;
    }

    @Override
    public PermissionsState getPermissionsState() {
        return (sharedUser != null)
                ? sharedUser.getPermissionsState()
                : super.getPermissionsState();
    }

    public PackageParser.Package getPackage() {
        return pkg;
    }

    public int getAppId() {
        return appId;
    }

    public void setInstallPermissionsFixed(boolean fixed) {
        installPermissionsFixed = fixed;
    }

    public boolean areInstallPermissionsFixed() {
        return installPermissionsFixed;
    }

    public boolean isPrivileged() {
        return (pkgPrivateFlags & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0;
    }

    public boolean isOem() {
        return (pkgPrivateFlags & ApplicationInfo.PRIVATE_FLAG_OEM) != 0;
    }

    public boolean isVendor() {
        return (pkgPrivateFlags & ApplicationInfo.PRIVATE_FLAG_VENDOR) != 0;
    }

    public boolean isProduct() {
        return (pkgPrivateFlags & ApplicationInfo.PRIVATE_FLAG_PRODUCT) != 0;
    }

    public boolean isProductServices() {
        return (pkgPrivateFlags & ApplicationInfo.PRIVATE_FLAG_PRODUCT_SERVICES) != 0;
    }

    public boolean isOdm() {
        return (pkgPrivateFlags & ApplicationInfo.PRIVATE_FLAG_ODM) != 0;
    }

    public boolean isSystem() {
        return (pkgFlags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    public boolean isUpdatedSystem() {
        return (pkgFlags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
    }

    @Override
    public boolean isSharedUser() {
        return sharedUser != null;
    }

    public boolean isMatch(int flags) {
        if ((flags & PackageManager.MATCH_SYSTEM_ONLY) != 0) {
            return isSystem();
        }
        return true;
    }

    public boolean hasChildPackages() {
        return childPackageNames != null && !childPackageNames.isEmpty();
    }

    public void writeToProto(ProtoOutputStream proto, long fieldId, List<UserInfo> users) {
        final long packageToken = proto.start(fieldId);
        proto.write(PackageProto.NAME, (realName != null ? realName : name));
        proto.write(PackageProto.UID, appId);
        proto.write(PackageProto.VERSION_CODE, versionCode);
        proto.write(PackageProto.INSTALL_TIME_MS, firstInstallTime);
        proto.write(PackageProto.UPDATE_TIME_MS, lastUpdateTime);
        proto.write(PackageProto.INSTALLER_NAME, installerPackageName);

        if (pkg != null) {
            proto.write(PackageProto.VERSION_STRING, pkg.mVersionName);

            long splitToken = proto.start(PackageProto.SPLITS);
            proto.write(PackageProto.SplitProto.NAME, "base");
            proto.write(PackageProto.SplitProto.REVISION_CODE, pkg.baseRevisionCode);
            proto.end(splitToken);

            if (pkg.splitNames != null) {
                for (int i = 0; i < pkg.splitNames.length; i++) {
                    splitToken = proto.start(PackageProto.SPLITS);
                    proto.write(PackageProto.SplitProto.NAME, pkg.splitNames[i]);
                    proto.write(PackageProto.SplitProto.REVISION_CODE, pkg.splitRevisionCodes[i]);
                    proto.end(splitToken);
                }
            }
        }
        writeUsersInfoToProto(proto, PackageProto.USERS);
        proto.end(packageToken);
    }

    /** Updates all fields in the current setting from another. */
    public void updateFrom(PackageSetting other) {
        super.updateFrom(other);
        appId = other.appId;
        pkg = other.pkg;
        sharedUserId = other.sharedUserId;
        sharedUser = other.sharedUser;
    }
}
