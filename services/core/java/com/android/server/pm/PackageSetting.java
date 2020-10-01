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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.service.pm.PackageProto;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.permission.PermissionsState;
import com.android.server.pm.pkg.PackageStateUnserialized;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Settings data for a particular package we know about.
 */
public class PackageSetting extends PackageSettingBase {
    int appId;

    @Nullable
    public AndroidPackage pkg;
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

    /**
     *  Maps mime group name to the set of Mime types in a group. Mime groups declared
     *  by app are populated with empty sets at construction.
     *  Mime groups can not be created/removed at runtime, thus keys in this map should not change
     */
    @Nullable
    Map<String, ArraySet<String>> mimeGroups;

    @NonNull
    private PackageStateUnserialized pkgState = new PackageStateUnserialized();

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public PackageSetting(String name, String realName, File codePath, File resourcePath,
            String legacyNativeLibraryPathString, String primaryCpuAbiString,
            String secondaryCpuAbiString, String cpuAbiOverrideString,
            long pVersionCode, int pkgFlags, int privateFlags,
            int sharedUserId, String[] usesStaticLibraries,
            long[] usesStaticLibrariesVersions, Map<String, ArraySet<String>> mimeGroups) {
        super(name, realName, codePath, resourcePath, legacyNativeLibraryPathString,
                primaryCpuAbiString, secondaryCpuAbiString, cpuAbiOverrideString,
                pVersionCode, pkgFlags, privateFlags,
                usesStaticLibraries, usesStaticLibrariesVersions);
        this.sharedUserId = sharedUserId;
        copyMimeGroups(mimeGroups);
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
        copyMimeGroups(orig.mimeGroups);
    }

    private void copyMimeGroups(@Nullable Map<String, ArraySet<String>> newMimeGroups) {
        if (newMimeGroups == null) {
            mimeGroups = null;
            return;
        }

        mimeGroups = new ArrayMap<>(newMimeGroups.size());
        for (String mimeGroup : newMimeGroups.keySet()) {
            ArraySet<String> mimeTypes = newMimeGroups.get(mimeGroup);

            if (mimeTypes != null) {
                mimeGroups.put(mimeGroup, new ArraySet<>(mimeTypes));
            } else {
                mimeGroups.put(mimeGroup, new ArraySet<>());
            }
        }
    }

    /**
     * Updates declared MIME groups, removing no longer declared groups
     * and keeping previous state of MIME groups
     */
    void updateMimeGroups(@Nullable Set<String> newMimeGroupNames) {
        if (newMimeGroupNames == null) {
            mimeGroups = null;
            return;
        }

        if (mimeGroups == null) {
            // set mimeGroups to empty map to avoid repeated null-checks in the next loop
            mimeGroups = Collections.emptyMap();
        }

        ArrayMap<String, ArraySet<String>> updatedMimeGroups =
                new ArrayMap<>(newMimeGroupNames.size());

        for (String mimeGroup : newMimeGroupNames) {
            if (mimeGroups.containsKey(mimeGroup)) {
                updatedMimeGroups.put(mimeGroup, mimeGroups.get(mimeGroup));
            } else {
                updatedMimeGroups.put(mimeGroup, new ArraySet<>());
            }
        }
        mimeGroups = updatedMimeGroups;
    }

    @Override
    public PermissionsState getPermissionsState() {
        return (sharedUser != null)
                ? sharedUser.getPermissionsState()
                : super.getPermissionsState();
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

    // TODO(b/135203078): Remove these in favor of reading from the package directly
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

    public boolean isSystemExt() {
        return (pkgPrivateFlags & ApplicationInfo.PRIVATE_FLAG_SYSTEM_EXT) != 0;
    }

    public boolean isOdm() {
        return (pkgPrivateFlags & ApplicationInfo.PRIVATE_FLAG_ODM) != 0;
    }

    public boolean isSystem() {
        return (pkgFlags & ApplicationInfo.FLAG_SYSTEM) != 0;
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

    public boolean setMimeGroup(String mimeGroup, List<String> mimeTypes) {
        ArraySet<String> oldMimeTypes = getMimeGroupInternal(mimeGroup);
        if (oldMimeTypes == null) {
            throw new IllegalArgumentException("Unknown MIME group " + mimeGroup
                    + " for package " + name);
        }

        ArraySet<String> newMimeTypes = new ArraySet<>(mimeTypes);
        boolean hasChanges = !newMimeTypes.equals(oldMimeTypes);
        mimeGroups.put(mimeGroup, newMimeTypes);
        return hasChanges;
    }

    public List<String> getMimeGroup(String mimeGroup) {
        ArraySet<String> mimeTypes = getMimeGroupInternal(mimeGroup);
        if (mimeTypes == null) {
            throw new IllegalArgumentException("Unknown MIME group " + mimeGroup
                    + " for package " + name);
        }
        return new ArrayList<>(mimeTypes);
    }

    private ArraySet<String> getMimeGroupInternal(String mimeGroup) {
        return mimeGroups != null ? mimeGroups.get(mimeGroup) : null;
    }

    public void dumpDebug(ProtoOutputStream proto, long fieldId, List<UserInfo> users) {
        final long packageToken = proto.start(fieldId);
        proto.write(PackageProto.NAME, (realName != null ? realName : name));
        proto.write(PackageProto.UID, appId);
        proto.write(PackageProto.VERSION_CODE, versionCode);
        proto.write(PackageProto.INSTALL_TIME_MS, firstInstallTime);
        proto.write(PackageProto.UPDATE_TIME_MS, lastUpdateTime);
        proto.write(PackageProto.INSTALLER_NAME, installSource.installerPackageName);

        if (pkg != null) {
            proto.write(PackageProto.VERSION_STRING, pkg.getVersionName());

            long splitToken = proto.start(PackageProto.SPLITS);
            proto.write(PackageProto.SplitProto.NAME, "base");
            proto.write(PackageProto.SplitProto.REVISION_CODE, pkg.getBaseRevisionCode());
            proto.end(splitToken);

            if (pkg.getSplitNames() != null) {
                for (int i = 0; i < pkg.getSplitNames().length; i++) {
                    splitToken = proto.start(PackageProto.SPLITS);
                    proto.write(PackageProto.SplitProto.NAME, pkg.getSplitNames()[i]);
                    proto.write(PackageProto.SplitProto.REVISION_CODE,
                            pkg.getSplitRevisionCodes()[i]);
                    proto.end(splitToken);
                }
            }

            long sourceToken = proto.start(PackageProto.INSTALL_SOURCE);
            proto.write(PackageProto.InstallSourceProto.INITIATING_PACKAGE_NAME,
                    installSource.initiatingPackageName);
            proto.write(PackageProto.InstallSourceProto.ORIGINATING_PACKAGE_NAME,
                    installSource.originatingPackageName);
            proto.end(sourceToken);
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

        Set<String> mimeGroupNames = other.mimeGroups != null ? other.mimeGroups.keySet() : null;
        updateMimeGroups(mimeGroupNames);

        getPkgState().updateFrom(other.getPkgState());
    }

    @NonNull
    public PackageStateUnserialized getPkgState() {
        return pkgState;
    }
}
