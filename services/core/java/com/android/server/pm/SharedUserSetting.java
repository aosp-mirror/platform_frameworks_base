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

import android.annotation.Nullable;
import android.content.pm.ApplicationInfo;
import android.content.pm.parsing.component.ParsedProcess;
import android.service.pm.PackageServiceDumpProto;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.ArrayUtils;
import com.android.server.pm.parsing.pkg.AndroidPackage;

import libcore.util.EmptyArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Settings data for a particular shared user ID we know about.
 */
public final class SharedUserSetting extends SettingBase {
    final String name;

    int userId;

    // flags that are associated with this uid, regardless of any package flags
    int uidFlags;
    int uidPrivateFlags;

    // The lowest targetSdkVersion of all apps in the sharedUserSetting, used to assign seinfo so
    // that all apps within the sharedUser run in the same selinux context.
    int seInfoTargetSdkVersion;

    final ArraySet<PackageSetting> packages = new ArraySet<>();

    final PackageSignatures signatures = new PackageSignatures();
    Boolean signaturesChanged;

    ArrayMap<String, ParsedProcess> processes;

    SharedUserSetting(String _name, int _pkgFlags, int _pkgPrivateFlags) {
        super(_pkgFlags, _pkgPrivateFlags);
        uidFlags =  _pkgFlags;
        uidPrivateFlags = _pkgPrivateFlags;
        name = _name;
        seInfoTargetSdkVersion = android.os.Build.VERSION_CODES.CUR_DEVELOPMENT;
    }

    @Override
    public String toString() {
        return "SharedUserSetting{" + Integer.toHexString(System.identityHashCode(this)) + " "
                + name + "/" + userId + "}";
    }

    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        proto.write(PackageServiceDumpProto.SharedUserProto.UID, userId);
        proto.write(PackageServiceDumpProto.SharedUserProto.NAME, name);
        proto.end(token);
    }

    void addProcesses(Map<String, ParsedProcess> newProcs) {
        if (newProcs != null) {
            final int numProcs = newProcs.size();
            if (processes == null) {
                processes = new ArrayMap<>(numProcs);
            }
            for (String key : newProcs.keySet()) {
                ParsedProcess newProc = newProcs.get(key);
                ParsedProcess proc = processes.get(newProc.getName());
                if (proc == null) {
                    proc = new ParsedProcess(newProc);
                    processes.put(newProc.getName(), proc);
                } else {
                    proc.addStateFrom(newProc);
                }
            }
        }
    }

    boolean removePackage(PackageSetting packageSetting) {
        if (!packages.remove(packageSetting)) {
            return false;
        }
        // recalculate the pkgFlags for this shared user if needed
        if ((this.pkgFlags & packageSetting.pkgFlags) != 0) {
            int aggregatedFlags = uidFlags;
            for (PackageSetting ps : packages) {
                aggregatedFlags |= ps.pkgFlags;
            }
            setFlags(aggregatedFlags);
        }
        if ((this.pkgPrivateFlags & packageSetting.pkgPrivateFlags) != 0) {
            int aggregatedPrivateFlags = uidPrivateFlags;
            for (PackageSetting ps : packages) {
                aggregatedPrivateFlags |= ps.pkgPrivateFlags;
            }
            setPrivateFlags(aggregatedPrivateFlags);
        }
        // recalculate processes.
        updateProcesses();
        return true;
    }

    void addPackage(PackageSetting packageSetting) {
        // If this is the first package added to this shared user, temporarily (until next boot) use
        // its targetSdkVersion when assigning seInfo for the shared user.
        if ((packages.size() == 0) && (packageSetting.pkg != null)) {
            seInfoTargetSdkVersion = packageSetting.pkg.getTargetSdkVersion();
        }
        if (packages.add(packageSetting)) {
            setFlags(this.pkgFlags | packageSetting.pkgFlags);
            setPrivateFlags(this.pkgPrivateFlags | packageSetting.pkgPrivateFlags);
        }
        if (packageSetting.pkg != null) {
            addProcesses(packageSetting.pkg.getProcesses());
        }
    }

    public @Nullable List<AndroidPackage> getPackages() {
        if (packages == null || packages.size() == 0) {
            return null;
        }
        final ArrayList<AndroidPackage> pkgList = new ArrayList<>(packages.size());
        for (PackageSetting ps : packages) {
            if ((ps == null) || (ps.pkg == null)) {
                continue;
            }
            pkgList.add(ps.pkg);
        }
        return pkgList;
    }

    public boolean isPrivileged() {
        return (this.pkgPrivateFlags & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0;
    }

    /**
     * Determine the targetSdkVersion for a sharedUser and update pkg.applicationInfo.seInfo
     * to ensure that all apps within the sharedUser share an SELinux domain. Use the lowest
     * targetSdkVersion of all apps within the shared user, which corresponds to the least
     * restrictive selinux domain.
     */
    public void fixSeInfoLocked() {
        if (packages == null || packages.size() == 0) {
            return;
        }
        for (PackageSetting ps : packages) {
            if ((ps == null) || (ps.pkg == null)) {
                continue;
            }
            if (ps.pkg.getTargetSdkVersion() < seInfoTargetSdkVersion) {
                seInfoTargetSdkVersion = ps.pkg.getTargetSdkVersion();
            }
        }

        for (PackageSetting ps : packages) {
            if ((ps == null) || (ps.pkg == null)) {
                continue;
            }
            final boolean isPrivileged = isPrivileged() | ps.pkg.isPrivileged();
            ps.getPkgState().setOverrideSeInfo(SELinuxMMAC.getSeInfo(ps.pkg, isPrivileged,
                    seInfoTargetSdkVersion));
        }
    }

    /**
     * Update tracked data about processes based on all known packages in the shared user ID.
     */
    public void updateProcesses() {
        processes = null;
        for (int i = packages.size() - 1; i >= 0; i--) {
            final AndroidPackage pkg = packages.valueAt(i).pkg;
            if (pkg != null) {
                addProcesses(pkg.getProcesses());
            }
        }
    }

    /** Returns userIds which doesn't have any packages with this sharedUserId */
    public int[] getNotInstalledUserIds() {
        int[] excludedUserIds = null;
        for (PackageSetting ps : packages) {
            final int[] userIds = ps.getNotInstalledUserIds();
            if (excludedUserIds == null) {
                excludedUserIds = userIds;
            } else {
                for (int userId : excludedUserIds) {
                    if (!ArrayUtils.contains(userIds, userId)) {
                        excludedUserIds = ArrayUtils.removeInt(excludedUserIds, userId);
                    }
                }
            }
        }
        return excludedUserIds == null ? EmptyArray.INT : excludedUserIds;
    }

    /** Updates all fields in this shared user setting from another. */
    public SharedUserSetting updateFrom(SharedUserSetting sharedUser) {
        copyFrom(sharedUser);
        this.userId = sharedUser.userId;
        this.uidFlags = sharedUser.uidFlags;
        this.uidPrivateFlags = sharedUser.uidPrivateFlags;
        this.seInfoTargetSdkVersion = sharedUser.seInfoTargetSdkVersion;
        this.packages.clear();
        this.packages.addAll(sharedUser.packages);
        this.signaturesChanged = sharedUser.signaturesChanged;
        if (sharedUser.processes != null) {
            final int numProcs = sharedUser.processes.size();
            this.processes = new ArrayMap<>(numProcs);
            for (int i = 0; i < numProcs; i++) {
                ParsedProcess proc =
                        new ParsedProcess(sharedUser.processes.valueAt(i));
                this.processes.put(proc.getName(), proc);
            }
        } else {
            this.processes = null;
        }
        return this;
    }
}
