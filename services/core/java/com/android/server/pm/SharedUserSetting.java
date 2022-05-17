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
import android.content.pm.SigningDetails;
import android.service.pm.PackageServiceDumpProto;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.ArrayUtils;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.permission.LegacyPermissionState;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.SharedUserApi;
import com.android.server.pm.pkg.component.ComponentMutateUtils;
import com.android.server.pm.pkg.component.ParsedProcess;
import com.android.server.pm.pkg.component.ParsedProcessImpl;
import com.android.server.utils.SnapshotCache;
import com.android.server.utils.Watchable;
import com.android.server.utils.WatchedArraySet;
import com.android.server.utils.Watcher;

import libcore.util.EmptyArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Settings data for a particular shared user ID we know about.
 */
public final class SharedUserSetting extends SettingBase implements SharedUserApi {
    final String name;

    int mAppId;

    /** @see SharedUserApi#getUidFlags() **/
    int uidFlags;
    int uidPrivateFlags;

    /** @see SharedUserApi#getSeInfoTargetSdkVersion() **/
    int seInfoTargetSdkVersion;

    private final WatchedArraySet<PackageSetting> mPackages;
    private final SnapshotCache<WatchedArraySet<PackageSetting>> mPackagesSnapshot;

    // It is possible for a system app to leave shared user ID by an update.
    // We need to keep track of the shadowed PackageSettings so that it is possible to uninstall
    // the update and revert the system app back into the original shared user ID.
    final WatchedArraySet<PackageSetting> mDisabledPackages;
    private final SnapshotCache<WatchedArraySet<PackageSetting>> mDisabledPackagesSnapshot;

    /**
     * The observer that watches for changes from array members
     */
    private final Watcher mObserver = new Watcher() {
        @Override
        public void onChange(@Nullable Watchable what) {
            SharedUserSetting.this.onChanged();
        }
    };

    final PackageSignatures signatures = new PackageSignatures();
    Boolean signaturesChanged;

    final ArrayMap<String, ParsedProcess> processes;

    /**
     * Snapshot support.
     */
    private final SnapshotCache<SharedUserSetting> mSnapshot;

    private SnapshotCache<SharedUserSetting> makeCache() {
        return new SnapshotCache<SharedUserSetting>(this, this) {
            @Override
            public SharedUserSetting createSnapshot() {
                return new SharedUserSetting(mSource);
            }};
    }

    SharedUserSetting(String _name, int _pkgFlags, int _pkgPrivateFlags) {
        super(_pkgFlags, _pkgPrivateFlags);
        uidFlags =  _pkgFlags;
        uidPrivateFlags = _pkgPrivateFlags;
        name = _name;
        seInfoTargetSdkVersion = android.os.Build.VERSION_CODES.CUR_DEVELOPMENT;
        mPackages = new WatchedArraySet<>();
        mPackagesSnapshot = new SnapshotCache.Auto<>(mPackages, mPackages,
                "SharedUserSetting.packages");
        mDisabledPackages = new WatchedArraySet<>();
        mDisabledPackagesSnapshot = new SnapshotCache.Auto<>(mDisabledPackages, mDisabledPackages,
                "SharedUserSetting.mDisabledPackages");
        processes = new ArrayMap<>();
        registerObservers();
        mSnapshot = makeCache();
    }

    // The copy constructor is used to create a snapshot
    private SharedUserSetting(SharedUserSetting orig) {
        super(orig);
        name = orig.name;
        mAppId = orig.mAppId;
        uidFlags = orig.uidFlags;
        uidPrivateFlags = orig.uidPrivateFlags;
        mPackages = orig.mPackagesSnapshot.snapshot();
        mPackagesSnapshot = new SnapshotCache.Sealed<>();
        mDisabledPackages = orig.mDisabledPackagesSnapshot.snapshot();
        mDisabledPackagesSnapshot = new SnapshotCache.Sealed<>();
        // A SigningDetails seems to consist solely of final attributes, so
        // it is safe to copy the reference.
        signatures.mSigningDetails = orig.signatures.mSigningDetails;
        signaturesChanged = orig.signaturesChanged;
        processes = new ArrayMap<>(orig.processes);
        mSnapshot = new SnapshotCache.Sealed<>();
    }

    private void registerObservers() {
        mPackages.registerObserver(mObserver);
        mDisabledPackages.registerObserver(mObserver);
    }

    /**
     * Return a read-only snapshot of this object.
     */
    public SharedUserSetting snapshot() {
        return mSnapshot.snapshot();
    }

    @Override
    public String toString() {
        return "SharedUserSetting{" + Integer.toHexString(System.identityHashCode(this)) + " "
                + name + "/" + mAppId + "}";
    }

    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        proto.write(PackageServiceDumpProto.SharedUserProto.UID, mAppId);
        proto.write(PackageServiceDumpProto.SharedUserProto.NAME, name);
        proto.end(token);
    }

    void addProcesses(Map<String, ParsedProcess> newProcs) {
        if (newProcs != null) {
            for (String key : newProcs.keySet()) {
                ParsedProcess newProc = newProcs.get(key);
                ParsedProcess proc = processes.get(newProc.getName());
                if (proc == null) {
                    proc = new ParsedProcessImpl(newProc);
                    processes.put(newProc.getName(), proc);
                } else {
                    ComponentMutateUtils.addStateFrom(proc, newProc);
                }
            }
            onChanged();
        }
    }

    boolean removePackage(PackageSetting packageSetting) {
        if (!mPackages.remove(packageSetting)) {
            return false;
        }
        // recalculate the pkgFlags for this shared user if needed
        if ((this.getFlags() & packageSetting.getFlags()) != 0) {
            int aggregatedFlags = uidFlags;
            for (int i = 0; i < mPackages.size(); i++) {
                PackageSetting ps = mPackages.valueAt(i);
                aggregatedFlags |= ps.getFlags();
            }
            setFlags(aggregatedFlags);
        }
        if ((this.getPrivateFlags() & packageSetting.getPrivateFlags()) != 0) {
            int aggregatedPrivateFlags = uidPrivateFlags;
            for (int i = 0; i < mPackages.size(); i++) {
                PackageSetting ps = mPackages.valueAt(i);
                aggregatedPrivateFlags |= ps.getPrivateFlags();
            }
            setPrivateFlags(aggregatedPrivateFlags);
        }
        // recalculate processes.
        updateProcesses();
        onChanged();
        return true;
    }

    void addPackage(PackageSetting packageSetting) {
        // If this is the first package added to this shared user, temporarily (until next boot) use
        // its targetSdkVersion when assigning seInfo for the shared user.
        if ((mPackages.size() == 0) && (packageSetting.getPkg() != null)) {
            seInfoTargetSdkVersion = packageSetting.getPkg().getTargetSdkVersion();
        }
        if (mPackages.add(packageSetting)) {
            setFlags(this.getFlags() | packageSetting.getFlags());
            setPrivateFlags(this.getPrivateFlags() | packageSetting.getPrivateFlags());
            onChanged();
        }
        if (packageSetting.getPkg() != null) {
            addProcesses(packageSetting.getPkg().getProcesses());
        }
    }

    @NonNull
    @Override
    public List<AndroidPackage> getPackages() {
        if (mPackages == null || mPackages.size() == 0) {
            return Collections.emptyList();
        }
        final ArrayList<AndroidPackage> pkgList = new ArrayList<>(mPackages.size());
        for (int i = 0; i < mPackages.size(); i++) {
            PackageSetting ps = mPackages.valueAt(i);
            if ((ps == null) || (ps.getPkg() == null)) {
                continue;
            }
            pkgList.add(ps.getPkg());
        }
        return pkgList;
    }

    @Override
    public boolean isPrivileged() {
        return (this.getPrivateFlags() & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0;
    }

    /**
     * A shared user is considered "single user" if there is exactly one single package
     * currently using it. In the case when that package is also a system app, the APK on
     * the system partition has to also leave shared UID.
     */
    public boolean isSingleUser() {
        if (mPackages.size() != 1) {
            return false;
        }
        if (mDisabledPackages.size() > 1) {
            return false;
        }
        if (mDisabledPackages.size() == 1) {
            final AndroidPackage pkg = mDisabledPackages.valueAt(0).getPkg();
            return pkg != null && pkg.isLeavingSharedUid();
        }
        return true;
    }

    /**
     * Determine the targetSdkVersion for a sharedUser and update pkg.applicationInfo.seInfo
     * to ensure that all apps within the sharedUser share an SELinux domain. Use the lowest
     * targetSdkVersion of all apps within the shared user, which corresponds to the least
     * restrictive selinux domain.
     */
    public void fixSeInfoLocked() {
        if (mPackages == null || mPackages.size() == 0) {
            return;
        }
        for (int i = 0; i < mPackages.size(); i++) {
            PackageSetting ps = mPackages.valueAt(i);
            if ((ps == null) || (ps.getPkg() == null)) {
                continue;
            }
            if (ps.getPkg().getTargetSdkVersion() < seInfoTargetSdkVersion) {
                seInfoTargetSdkVersion = ps.getPkg().getTargetSdkVersion();
                onChanged();
            }
        }

        for (int i = 0; i < mPackages.size(); i++) {
            PackageSetting ps = mPackages.valueAt(i);
            if ((ps == null) || (ps.getPkg() == null)) {
                continue;
            }
            final boolean isPrivileged = isPrivileged() | ps.getPkg().isPrivileged();
            ps.getPkgState().setOverrideSeInfo(SELinuxMMAC.getSeInfo(ps.getPkg(), isPrivileged,
                    seInfoTargetSdkVersion));
            onChanged();
        }
    }

    /**
     * Update tracked data about processes based on all known packages in the shared user ID.
     */
    public void updateProcesses() {
        processes.clear();
        for (int i = mPackages.size() - 1; i >= 0; i--) {
            final AndroidPackage pkg = mPackages.valueAt(i).getPkg();
            if (pkg != null) {
                addProcesses(pkg.getProcesses());
            }
        }
    }

    /** Returns userIds which doesn't have any packages with this sharedUserId */
    public int[] getNotInstalledUserIds() {
        int[] excludedUserIds = null;
        for (int i = 0; i < mPackages.size(); i++) {
            PackageSetting ps = mPackages.valueAt(i);
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
        super.copySettingBase(sharedUser);
        this.mAppId = sharedUser.mAppId;
        this.uidFlags = sharedUser.uidFlags;
        this.uidPrivateFlags = sharedUser.uidPrivateFlags;
        this.seInfoTargetSdkVersion = sharedUser.seInfoTargetSdkVersion;
        this.mPackages.clear();
        this.mPackages.addAll(sharedUser.mPackages);
        this.signaturesChanged = sharedUser.signaturesChanged;
        if (sharedUser.processes != null) {
            final int numProcs = sharedUser.processes.size();
            this.processes.clear();
            this.processes.ensureCapacity(numProcs);
            for (int i = 0; i < numProcs; i++) {
                ParsedProcess proc = new ParsedProcessImpl(sharedUser.processes.valueAt(i));
                this.processes.put(proc.getName(), proc);
            }
        } else {
            this.processes.clear();
        }
        onChanged();
        return this;
    }

    @NonNull
    @Override
    public String getName() {
        return name;
    }

    @Override
    public int getAppId() {
        return mAppId;
    }

    @Override
    public int getUidFlags() {
        return uidFlags;
    }

    @Override
    public int getPrivateUidFlags() {
        return uidPrivateFlags;
    }

    @Override
    public int getSeInfoTargetSdkVersion() {
        return seInfoTargetSdkVersion;
    }

    public WatchedArraySet<PackageSetting> getPackageSettings() {
        return mPackages;
    }

    public WatchedArraySet<PackageSetting> getDisabledPackageSettings() {
        return mDisabledPackages;
    }

    @NonNull
    @Override
    public ArraySet<? extends PackageStateInternal> getPackageStates() {
        return mPackages.untrackedStorage();
    }

    @NonNull
    @Override
    public ArraySet<? extends PackageStateInternal> getDisabledPackageStates() {
        return mDisabledPackages.untrackedStorage();
    }

    @NonNull
    @Override
    public SigningDetails getSigningDetails() {
        return signatures.mSigningDetails;
    }

    @NonNull
    @Override
    public ArrayMap<String, ParsedProcess> getProcesses() {
        return processes;
    }

    @NonNull
    @Override
    public LegacyPermissionState getSharedUserLegacyPermissionState() {
        return super.getLegacyPermissionState();
    }
}
