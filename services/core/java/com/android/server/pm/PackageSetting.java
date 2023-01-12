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

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.SigningDetails;
import android.content.pm.SigningInfo;
import android.content.pm.UserInfo;
import android.content.pm.overlay.OverlayPaths;
import android.os.UserHandle;
import android.service.pm.PackageProto;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.DataClass;
import com.android.server.pm.parsing.pkg.AndroidPackageInternal;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.pm.permission.LegacyPermissionDataProvider;
import com.android.server.pm.permission.LegacyPermissionState;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageState;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.PackageStateUnserialized;
import com.android.server.pm.pkg.PackageUserState;
import com.android.server.pm.pkg.PackageUserStateImpl;
import com.android.server.pm.pkg.PackageUserStateInternal;
import com.android.server.pm.pkg.SharedLibrary;
import com.android.server.pm.pkg.SharedLibraryWrapper;
import com.android.server.pm.pkg.SuspendParams;
import com.android.server.utils.SnapshotCache;
import com.android.server.utils.WatchedArraySet;

import libcore.util.EmptyArray;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Settings data for a particular package we know about.
 *
 * @hide
 */
@DataClass(genGetters = true, genConstructor = false, genSetters = false, genBuilder = false)
@DataClass.Suppress({"getSnapshot", })
public class PackageSetting extends SettingBase implements PackageStateInternal {

    /**
     * The shared user ID lets us link this object to {@link SharedUserSetting}.
     */
    private int mSharedUserAppId;

    @Nullable
    private Map<String, Set<String>> mimeGroups;

    @Deprecated
    @Nullable
    private Set<String> mOldCodePaths;

    @Nullable
    private String[] usesSdkLibraries;

    @Nullable
    private long[] usesSdkLibrariesVersionsMajor;

    @Nullable
    private String[] usesStaticLibraries;

    @Nullable
    private long[] usesStaticLibrariesVersions;

    /**
     * The path under which native libraries have been unpacked. This path is
     * always derived at runtime, and is only stored here for cleanup when a
     * package is uninstalled.
     */
    @Nullable
    @Deprecated
    private String legacyNativeLibraryPath;

    @NonNull
    private String mName;

    @Nullable
    private String mRealName;

    private int mAppId;

    /**
     * It is expected that all code that uses a {@link PackageSetting} understands this inner field
     * may be null. Note that this relationship only works one way. It should not be possible to
     * have an entry inside {@link PackageManagerService#mPackages} without a corresponding
     * {@link PackageSetting} inside {@link Settings#mPackages}.
     *
     * @see PackageState#getAndroidPackage()
     */
    @Nullable
    private AndroidPackageInternal pkg;

    /** @see AndroidPackage#getPath() */
    @NonNull
    private File mPath;
    @NonNull
    private String mPathString;

    private float mLoadingProgress;

    @Nullable
    private String mPrimaryCpuAbi;

    @Nullable
    private String mSecondaryCpuAbi;

    @Nullable
    private String mCpuAbiOverride;

    private long mLastModifiedTime;
    private long lastUpdateTime;
    private long versionCode;

    @NonNull
    private PackageSignatures signatures;

    private boolean installPermissionsFixed;

    @NonNull
    private PackageKeySetData keySetData = new PackageKeySetData();

    // TODO: Access is not locked.
    @NonNull
    private final SparseArray<PackageUserStateImpl> mUserStates = new SparseArray<>();

    @NonNull
    private InstallSource installSource;

    /** @see PackageState#getVolumeUuid()  */
    @Nullable
    private String volumeUuid;

    /** @see PackageState#getCategoryOverride() */
    private int categoryOverride = ApplicationInfo.CATEGORY_UNDEFINED;

    /** @see PackageState#isUpdateAvailable() */
    private boolean updateAvailable;

    private boolean forceQueryableOverride;

    @NonNull
    private final PackageStateUnserialized pkgState = new PackageStateUnserialized(this);

    @NonNull
    private UUID mDomainSetId;

    /**
     * Snapshot support.
     */
    @NonNull
    private final SnapshotCache<PackageSetting> mSnapshot;

    private SnapshotCache<PackageSetting> makeCache() {
        return new SnapshotCache<PackageSetting>(this, this) {
            @Override
            public PackageSetting createSnapshot() {
                return new PackageSetting(mSource, true);
            }};
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public PackageSetting(String name, String realName, @NonNull File path,
            String legacyNativeLibraryPath, String primaryCpuAbi,
            String secondaryCpuAbi, String cpuAbiOverride,
            long longVersionCode, int pkgFlags, int pkgPrivateFlags,
            int sharedUserAppId,
            String[] usesSdkLibraries, long[] usesSdkLibrariesVersionsMajor,
            String[] usesStaticLibraries, long[] usesStaticLibrariesVersions,
            Map<String, Set<String>> mimeGroups,
            @NonNull UUID domainSetId) {
        super(pkgFlags, pkgPrivateFlags);
        this.mName = name;
        this.mRealName = realName;
        this.usesSdkLibraries = usesSdkLibraries;
        this.usesSdkLibrariesVersionsMajor = usesSdkLibrariesVersionsMajor;
        this.usesStaticLibraries = usesStaticLibraries;
        this.usesStaticLibrariesVersions = usesStaticLibrariesVersions;
        this.mPath = path;
        this.mPathString = path.toString();
        this.legacyNativeLibraryPath = legacyNativeLibraryPath;
        this.mPrimaryCpuAbi = primaryCpuAbi;
        this.mSecondaryCpuAbi = secondaryCpuAbi;
        this.mCpuAbiOverride = cpuAbiOverride;
        this.versionCode = longVersionCode;
        this.signatures = new PackageSignatures();
        this.installSource = InstallSource.EMPTY;
        this.mSharedUserAppId = sharedUserAppId;
        mDomainSetId = domainSetId;
        copyMimeGroups(mimeGroups);
        mSnapshot = makeCache();
    }

    /**
     * New instance of PackageSetting replicating the original settings.
     * Note that it keeps the same PackageParser.Package instance.
     */
    PackageSetting(PackageSetting orig) {
        this(orig, false);
    }

    /**
     * New instance of PackageSetting with one-level-deep cloning.
     * <p>
     * IMPORTANT: With a shallow copy, we do NOT create new contained objects.
     * This means, for example, changes to the user state of the original PackageSetting
     * will also change the user state in its copy.
     */
    PackageSetting(PackageSetting base, String realPkgName) {
        this(base, false);
        this.mRealName = realPkgName;
    }

    PackageSetting(@NonNull PackageSetting original, boolean sealedSnapshot)  {
        super(original);
        copyPackageSetting(original, sealedSnapshot);
        if (sealedSnapshot) {
            mSnapshot = new SnapshotCache.Sealed();
        } else {
            mSnapshot = makeCache();
        }
    }

    /**
     * Return the package snapshot.
     */
    public PackageSetting snapshot() {
        return mSnapshot.snapshot();
    }

    public void dumpDebug(ProtoOutputStream proto, long fieldId, List<UserInfo> users,
            LegacyPermissionDataProvider dataProvider) {
        final long packageToken = proto.start(fieldId);
        proto.write(PackageProto.NAME, (mRealName != null ? mRealName : mName));
        proto.write(PackageProto.UID, mAppId);
        proto.write(PackageProto.VERSION_CODE, versionCode);
        proto.write(PackageProto.UPDATE_TIME_MS, lastUpdateTime);
        proto.write(PackageProto.INSTALLER_NAME, installSource.mInstallerPackageName);

        if (pkg != null) {
            proto.write(PackageProto.VERSION_STRING, pkg.getVersionName());

            long splitToken = proto.start(PackageProto.SPLITS);
            proto.write(PackageProto.SplitProto.NAME, "base");
            proto.write(PackageProto.SplitProto.REVISION_CODE, pkg.getBaseRevisionCode());
            proto.end(splitToken);

            for (int i = 0; i < pkg.getSplitNames().length; i++) {
                splitToken = proto.start(PackageProto.SPLITS);
                proto.write(PackageProto.SplitProto.NAME, pkg.getSplitNames()[i]);
                proto.write(PackageProto.SplitProto.REVISION_CODE,
                        pkg.getSplitRevisionCodes()[i]);
                proto.end(splitToken);
            }

            long sourceToken = proto.start(PackageProto.INSTALL_SOURCE);
            proto.write(PackageProto.InstallSourceProto.INITIATING_PACKAGE_NAME,
                    installSource.mInitiatingPackageName);
            proto.write(PackageProto.InstallSourceProto.ORIGINATING_PACKAGE_NAME,
                    installSource.mOriginatingPackageName);
            proto.write(PackageProto.InstallSourceProto.UPDATE_OWNER_PACKAGE_NAME,
                    installSource.mUpdateOwnerPackageName);
            proto.end(sourceToken);
        }
        proto.write(PackageProto.StatesProto.IS_LOADING, isLoading());
        writeUsersInfoToProto(proto, PackageProto.USERS);
        writePackageUserPermissionsProto(proto, PackageProto.USER_PERMISSIONS, users, dataProvider);
        proto.end(packageToken);
    }

    public PackageSetting setAppId(int appId) {
        this.mAppId = appId;
        onChanged();
        return this;
    }

    public PackageSetting setCpuAbiOverride(String cpuAbiOverrideString) {
        this.mCpuAbiOverride = cpuAbiOverrideString;
        onChanged();
        return this;
    }

    /**
     * In case of replacing an old package, restore the first install timestamps if it was installed
     * for the same users
     */
    public PackageSetting setFirstInstallTimeFromReplaced(PackageStateInternal replacedPkgSetting,
            int[] userIds) {
        for (int userId = 0; userId < userIds.length; userId++) {
            final long previousFirstInstallTime =
                    replacedPkgSetting.getUserStateOrDefault(userId).getFirstInstallTimeMillis();
            if (previousFirstInstallTime != 0) {
                modifyUserState(userId).setFirstInstallTime(previousFirstInstallTime);
            }
        }
        onChanged();
        return this;
    }

    /**
     * Set the time for the first time when an app is installed for a user. If userId specifies all
     * users, set the same timestamp for all the users.
     */
    public PackageSetting setFirstInstallTime(long firstInstallTime, int userId) {
        if (userId == UserHandle.USER_ALL) {
            int userStateCount = mUserStates.size();
            for (int i = 0; i < userStateCount; i++) {
                mUserStates.valueAt(i).setFirstInstallTime(firstInstallTime);
            }
        } else {
            modifyUserState(userId).setFirstInstallTime(firstInstallTime);
        }
        onChanged();
        return this;
    }

    public PackageSetting setForceQueryableOverride(boolean forceQueryableOverride) {
        this.forceQueryableOverride = forceQueryableOverride;
        onChanged();
        return this;
    }

    public PackageSetting setInstallerPackage(@Nullable String installerPackageName,
            int installerPackageUid) {
        installSource = installSource.setInstallerPackage(installerPackageName,
                installerPackageUid);
        onChanged();
        return this;
    }

    public PackageSetting setUpdateOwnerPackage(@Nullable String updateOwnerPackageName) {
        installSource = installSource.setUpdateOwnerPackageName(updateOwnerPackageName);
        onChanged();
        return this;
    }

    public PackageSetting setInstallSource(InstallSource installSource) {
        this.installSource = Objects.requireNonNull(installSource);
        onChanged();
        return this;
    }

    PackageSetting removeInstallerPackage(@Nullable String packageName) {
        installSource = installSource.removeInstallerPackage(packageName);
        onChanged();
        return this;
    }

    public PackageSetting setIsOrphaned(boolean isOrphaned) {
        installSource = installSource.setIsOrphaned(isOrphaned);
        onChanged();
        return this;
    }

    public PackageSetting setKeySetData(PackageKeySetData keySetData) {
        this.keySetData = keySetData;
        onChanged();
        return this;
    }

    public PackageSetting setLastModifiedTime(long timeStamp) {
        this.mLastModifiedTime = timeStamp;
        onChanged();
        return this;
    }

    public PackageSetting setLastUpdateTime(long lastUpdateTime) {
        this.lastUpdateTime = lastUpdateTime;
        onChanged();
        return this;
    }

    public PackageSetting setLongVersionCode(long versionCode) {
        this.versionCode = versionCode;
        onChanged();
        return this;
    }

    public boolean setMimeGroup(String mimeGroup, ArraySet<String> newMimeTypes) {
        Set<String> oldMimeTypes = mimeGroups == null ? null : mimeGroups.get(mimeGroup);
        if (oldMimeTypes == null) {
            throw new IllegalArgumentException("Unknown MIME group " + mimeGroup
                    + " for package " + mName);
        }

        boolean hasChanges = !newMimeTypes.equals(oldMimeTypes);
        mimeGroups.put(mimeGroup, newMimeTypes);
        if (hasChanges) {
            onChanged();
        }
        return hasChanges;
    }

    // TODO: Remove, only commit package when it's actually finalized
    public PackageSetting setPkg(AndroidPackage pkg) {
        this.pkg = (AndroidPackageInternal) pkg;
        onChanged();
        return this;
    }

    /**
     * Notify {@link #onChanged()}  if the parameter {@code usesLibraryFiles} is different from
     * {@link #getUsesLibraryFiles()}.
     * @param usesLibraryFiles the new uses library files
     * @return {@code this}
     */
    public PackageSetting setPkgStateLibraryFiles(@NonNull Collection<String> usesLibraryFiles) {
        final Collection<String> oldUsesLibraryFiles = getUsesLibraryFiles();
        if (oldUsesLibraryFiles.size() != usesLibraryFiles.size()
                || !oldUsesLibraryFiles.containsAll(usesLibraryFiles)) {
            pkgState.setUsesLibraryFiles(new ArrayList<>(usesLibraryFiles));
            onChanged();
        }
        return this;
    }

    public PackageSetting setPrimaryCpuAbi(String primaryCpuAbiString) {
        this.mPrimaryCpuAbi = primaryCpuAbiString;
        onChanged();
        return this;
    }

    public PackageSetting setSecondaryCpuAbi(String secondaryCpuAbiString) {
        this.mSecondaryCpuAbi = secondaryCpuAbiString;
        onChanged();
        return this;
    }

    public PackageSetting setSignatures(PackageSignatures signatures) {
        this.signatures = signatures;
        onChanged();
        return this;
    }

    public PackageSetting setVolumeUuid(String volumeUuid) {
        this.volumeUuid = volumeUuid;
        onChanged();
        return this;
    }

    @Override
    public boolean isExternalStorage() {
        return (getFlags() & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0;
    }

    public PackageSetting setUpdateAvailable(boolean updateAvailable) {
        this.updateAvailable = updateAvailable;
        onChanged();
        return this;
    }

    public void setSharedUserAppId(int sharedUserAppId) {
        mSharedUserAppId = sharedUserAppId;
        onChanged();
    }

    @Override
    public int getSharedUserAppId() {
        return mSharedUserAppId;
    }

    @Override
    public boolean hasSharedUser() {
        return mSharedUserAppId > 0;
    }

    @Override
    public String toString() {
        return "PackageSetting{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + mName + "/" + mAppId + "}";
    }

    protected void copyMimeGroups(@Nullable Map<String, Set<String>> newMimeGroups) {
        if (newMimeGroups == null) {
            mimeGroups = null;
            return;
        }

        mimeGroups = new ArrayMap<>(newMimeGroups.size());
        for (String mimeGroup : newMimeGroups.keySet()) {
            Set<String> mimeTypes = newMimeGroups.get(mimeGroup);

            if (mimeTypes != null) {
                mimeGroups.put(mimeGroup, new ArraySet<>(mimeTypes));
            } else {
                mimeGroups.put(mimeGroup, new ArraySet<>());
            }
        }
    }

    /** Updates all fields in the current setting from another. */
    public void updateFrom(PackageSetting other) {
        copyPackageSetting(other, false /* sealedSnapshot */);

        Set<String> mimeGroupNames = other.mimeGroups != null ? other.mimeGroups.keySet() : null;
        updateMimeGroups(mimeGroupNames);

        onChanged();
    }

    /**
     * Updates declared MIME groups, removing no longer declared groups
     * and keeping previous state of MIME groups
     */
    PackageSetting updateMimeGroups(@Nullable Set<String> newMimeGroupNames) {
        if (newMimeGroupNames == null) {
            mimeGroups = null;
            return this;
        }

        if (mimeGroups == null) {
            // set mimeGroups to empty map to avoid repeated null-checks in the next loop
            mimeGroups = Collections.emptyMap();
        }

        ArrayMap<String, Set<String>> updatedMimeGroups =
                new ArrayMap<>(newMimeGroupNames.size());

        for (String mimeGroup : newMimeGroupNames) {
            if (mimeGroups.containsKey(mimeGroup)) {
                updatedMimeGroups.put(mimeGroup, mimeGroups.get(mimeGroup));
            } else {
                updatedMimeGroups.put(mimeGroup, new ArraySet<>());
            }
        }
        onChanged();
        mimeGroups = updatedMimeGroups;
        return this;
    }

    @Deprecated
    @Override
    public LegacyPermissionState getLegacyPermissionState() {
        return super.getLegacyPermissionState();
    }

    public PackageSetting setInstallPermissionsFixed(boolean installPermissionsFixed) {
        this.installPermissionsFixed = installPermissionsFixed;
        return this;
    }

    public boolean isPrivileged() {
        return (getPrivateFlags() & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0;
    }

    public boolean isOem() {
        return (getPrivateFlags() & ApplicationInfo.PRIVATE_FLAG_OEM) != 0;
    }

    public boolean isVendor() {
        return (getPrivateFlags() & ApplicationInfo.PRIVATE_FLAG_VENDOR) != 0;
    }

    public boolean isProduct() {
        return (getPrivateFlags() & ApplicationInfo.PRIVATE_FLAG_PRODUCT) != 0;
    }

    @Override
    public boolean isRequiredForSystemUser() {
        return (getPrivateFlags() & ApplicationInfo.PRIVATE_FLAG_REQUIRED_FOR_SYSTEM_USER) != 0;
    }

    public boolean isSystemExt() {
        return (getPrivateFlags() & ApplicationInfo.PRIVATE_FLAG_SYSTEM_EXT) != 0;
    }

    public boolean isOdm() {
        return (getPrivateFlags() & ApplicationInfo.PRIVATE_FLAG_ODM) != 0;
    }

    public boolean isSystem() {
        return (getFlags() & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    public SigningDetails getSigningDetails() {
        return signatures.mSigningDetails;
    }

    public PackageSetting setSigningDetails(SigningDetails signingDetails) {
        // TODO: Immutability
        signatures.mSigningDetails = signingDetails;
        onChanged();
        return this;
    }

    public void copyPackageSetting(PackageSetting other, boolean sealedSnapshot) {
        super.copySettingBase(other);
        mSharedUserAppId = other.mSharedUserAppId;
        mLoadingProgress = other.mLoadingProgress;
        legacyNativeLibraryPath = other.legacyNativeLibraryPath;
        mName = other.mName;
        mRealName = other.mRealName;
        mAppId = other.mAppId;
        pkg = other.pkg;
        mPath = other.mPath;
        mPathString = other.mPathString;
        mPrimaryCpuAbi = other.mPrimaryCpuAbi;
        mSecondaryCpuAbi = other.mSecondaryCpuAbi;
        mCpuAbiOverride = other.mCpuAbiOverride;
        mLastModifiedTime = other.mLastModifiedTime;
        lastUpdateTime = other.lastUpdateTime;
        versionCode = other.versionCode;
        signatures = other.signatures;
        installPermissionsFixed = other.installPermissionsFixed;
        keySetData = new PackageKeySetData(other.keySetData);
        installSource = other.installSource;
        volumeUuid = other.volumeUuid;
        categoryOverride = other.categoryOverride;
        updateAvailable = other.updateAvailable;
        forceQueryableOverride = other.forceQueryableOverride;
        mDomainSetId = other.mDomainSetId;

        usesSdkLibraries = other.usesSdkLibraries != null
                ? Arrays.copyOf(other.usesSdkLibraries,
                other.usesSdkLibraries.length) : null;
        usesSdkLibrariesVersionsMajor = other.usesSdkLibrariesVersionsMajor != null
                ? Arrays.copyOf(other.usesSdkLibrariesVersionsMajor,
                other.usesSdkLibrariesVersionsMajor.length) : null;

        usesStaticLibraries = other.usesStaticLibraries != null
                ? Arrays.copyOf(other.usesStaticLibraries,
                other.usesStaticLibraries.length) : null;
        usesStaticLibrariesVersions = other.usesStaticLibrariesVersions != null
                ? Arrays.copyOf(other.usesStaticLibrariesVersions,
                other.usesStaticLibrariesVersions.length) : null;
        mUserStates.clear();
        for (int i = 0; i < other.mUserStates.size(); i++) {
            if (sealedSnapshot) {
                mUserStates.put(other.mUserStates.keyAt(i),
                        other.mUserStates.valueAt(i).snapshot());
            } else {
                var userState = other.mUserStates.valueAt(i);
                userState.setWatchable(this);
                mUserStates.put(other.mUserStates.keyAt(i), userState);
            }
        }

        if (mOldCodePaths != null) {
            if (other.mOldCodePaths != null) {
                mOldCodePaths.clear();
                mOldCodePaths.addAll(other.mOldCodePaths);
            } else {
                mOldCodePaths = null;
            }
        }

        copyMimeGroups(other.mimeGroups);
        pkgState.updateFrom(other.pkgState);
        onChanged();
    }

    @VisibleForTesting
    PackageUserStateImpl modifyUserState(int userId) {
        PackageUserStateImpl state = mUserStates.get(userId);
        if (state == null) {
            state = new PackageUserStateImpl(this);
            mUserStates.put(userId, state);
            onChanged();
        }
        return state;
    }

    public PackageUserStateImpl getOrCreateUserState(@UserIdInt int userId) {
        PackageUserStateImpl state = mUserStates.get(userId);
        if (state == null) {
            state = new PackageUserStateImpl(this);
            mUserStates.put(userId, state);
        }
        return state;
    }

    @NonNull
    public PackageUserStateInternal readUserState(int userId) {
        PackageUserStateInternal state = mUserStates.get(userId);
        if (state == null) {
            return PackageUserStateInternal.DEFAULT;
        }
        return state;
    }

    void setEnabled(int state, int userId, String callingPackage) {
        modifyUserState(userId)
                .setEnabledState(state)
                .setLastDisableAppCaller(callingPackage);
        onChanged();
    }

    int getEnabled(int userId) {
        return readUserState(userId).getEnabledState();
    }

    void setInstalled(boolean inst, int userId) {
        modifyUserState(userId).setInstalled(inst);
        onChanged();
    }

    boolean getInstalled(int userId) {
        return readUserState(userId).isInstalled();
    }

    int getInstallReason(int userId) {
        return readUserState(userId).getInstallReason();
    }

    void setInstallReason(int installReason, int userId) {
        modifyUserState(userId).setInstallReason(installReason);
        onChanged();
    }

    int getUninstallReason(int userId) {
        return readUserState(userId).getUninstallReason();
    }

    void setUninstallReason(@PackageManager.UninstallReason int uninstallReason, int userId) {
        modifyUserState(userId).setUninstallReason(uninstallReason);
        onChanged();
    }

    @NonNull
    OverlayPaths getOverlayPaths(int userId) {
        return readUserState(userId).getOverlayPaths();
    }

    boolean setOverlayPathsForLibrary(String libName, OverlayPaths overlayPaths, int userId) {
        boolean changed = modifyUserState(userId)
                .setSharedLibraryOverlayPaths(libName, overlayPaths);
        onChanged();
        return changed;
    }

    boolean isAnyInstalled(int[] users) {
        for (int user: users) {
            if (readUserState(user).isInstalled()) {
                return true;
            }
        }
        return false;
    }

    int[] queryInstalledUsers(int[] users, boolean installed) {
        int num = 0;
        for (int user : users) {
            if (getInstalled(user) == installed) {
                num++;
            }
        }
        int[] res = new int[num];
        num = 0;
        for (int user : users) {
            if (getInstalled(user) == installed) {
                res[num] = user;
                num++;
            }
        }
        return res;
    }

    long getCeDataInode(int userId) {
        return readUserState(userId).getCeDataInode();
    }

    void setCeDataInode(long ceDataInode, int userId) {
        modifyUserState(userId).setCeDataInode(ceDataInode);
        onChanged();
    }

    boolean getStopped(int userId) {
        return readUserState(userId).isStopped();
    }

    void setStopped(boolean stop, int userId) {
        modifyUserState(userId).setStopped(stop);
        onChanged();
    }

    boolean getNotLaunched(int userId) {
        return readUserState(userId).isNotLaunched();
    }

    void setNotLaunched(boolean stop, int userId) {
        modifyUserState(userId).setNotLaunched(stop);
        onChanged();
    }

    boolean getHidden(int userId) {
        return readUserState(userId).isHidden();
    }

    void setHidden(boolean hidden, int userId) {
        modifyUserState(userId).setHidden(hidden);
        onChanged();
    }

    int getDistractionFlags(int userId) {
        return readUserState(userId).getDistractionFlags();
    }

    void setDistractionFlags(int distractionFlags, int userId) {
        modifyUserState(userId).setDistractionFlags(distractionFlags);
        onChanged();
    }

    public boolean getInstantApp(int userId) {
        return readUserState(userId).isInstantApp();
    }

    void setInstantApp(boolean instantApp, int userId) {
        modifyUserState(userId).setInstantApp(instantApp);
        onChanged();
    }

    boolean getVirtualPreload(int userId) {
        return readUserState(userId).isVirtualPreload();
    }

    void setVirtualPreload(boolean virtualPreload, int userId) {
        modifyUserState(userId).setVirtualPreload(virtualPreload);
        onChanged();
    }

    void setUserState(int userId, long ceDataInode, int enabled, boolean installed, boolean stopped,
            boolean notLaunched, boolean hidden, int distractionFlags,
            ArrayMap<String, SuspendParams> suspendParams, boolean instantApp,
            boolean virtualPreload, String lastDisableAppCaller,
            ArraySet<String> enabledComponents, ArraySet<String> disabledComponents,
            int installReason, int uninstallReason,
            String harmfulAppWarning, String splashScreenTheme,
            long firstInstallTime) {
        modifyUserState(userId)
                .setSuspendParams(suspendParams)
                .setCeDataInode(ceDataInode)
                .setEnabledState(enabled)
                .setInstalled(installed)
                .setStopped(stopped)
                .setNotLaunched(notLaunched)
                .setHidden(hidden)
                .setDistractionFlags(distractionFlags)
                .setLastDisableAppCaller(lastDisableAppCaller)
                .setEnabledComponents(enabledComponents)
                .setDisabledComponents(disabledComponents)
                .setInstallReason(installReason)
                .setUninstallReason(uninstallReason)
                .setInstantApp(instantApp)
                .setVirtualPreload(virtualPreload)
                .setHarmfulAppWarning(harmfulAppWarning)
                .setSplashScreenTheme(splashScreenTheme)
                .setFirstInstallTime(firstInstallTime);
        onChanged();
    }

    void setUserState(int userId, PackageUserStateInternal otherState) {
        setUserState(userId, otherState.getCeDataInode(), otherState.getEnabledState(),
                otherState.isInstalled(), otherState.isStopped(), otherState.isNotLaunched(),
                otherState.isHidden(), otherState.getDistractionFlags(),
                otherState.getSuspendParams() == null
                        ? null : otherState.getSuspendParams().untrackedStorage(),
                otherState.isInstantApp(),
                otherState.isVirtualPreload(), otherState.getLastDisableAppCaller(),
                otherState.getEnabledComponentsNoCopy() == null
                        ? null : otherState.getEnabledComponentsNoCopy().untrackedStorage(),
                otherState.getDisabledComponentsNoCopy() == null
                        ? null : otherState.getDisabledComponentsNoCopy().untrackedStorage(),
                otherState.getInstallReason(), otherState.getUninstallReason(),
                otherState.getHarmfulAppWarning(), otherState.getSplashScreenTheme(),
                otherState.getFirstInstallTimeMillis());
    }

    WatchedArraySet<String> getEnabledComponents(int userId) {
        return readUserState(userId).getEnabledComponentsNoCopy();
    }

    WatchedArraySet<String> getDisabledComponents(int userId) {
        return readUserState(userId).getDisabledComponentsNoCopy();
    }

    /** Test only */
    void setEnabledComponents(WatchedArraySet<String> components, int userId) {
        modifyUserState(userId).setEnabledComponents(components);
        onChanged();
    }

    /** Test only */
    void setDisabledComponents(WatchedArraySet<String> components, int userId) {
        modifyUserState(userId).setDisabledComponents(components);
        onChanged();
    }

    void setEnabledComponentsCopy(WatchedArraySet<String> components, int userId) {
        modifyUserState(userId).setEnabledComponents(components != null
                ? components.untrackedStorage() : null);
        onChanged();
    }

    void setDisabledComponentsCopy(WatchedArraySet<String> components, int userId) {
        modifyUserState(userId).setDisabledComponents(components != null
                ? components.untrackedStorage() : null);
        onChanged();
    }

    PackageUserStateImpl modifyUserStateComponents(int userId, boolean disabled,
            boolean enabled) {
        PackageUserStateImpl state = modifyUserState(userId);
        boolean changed = false;
        if (disabled && state.getDisabledComponentsNoCopy() == null) {
            state.setDisabledComponents(new ArraySet<String>(1));
            changed = true;
        }
        if (enabled && state.getEnabledComponentsNoCopy() == null) {
            state.setEnabledComponents(new ArraySet<String>(1));
            changed = true;
        }
        if (changed) {
            onChanged();
        }
        return state;
    }

    void addDisabledComponent(String componentClassName, int userId) {
        modifyUserStateComponents(userId, true, false)
                .getDisabledComponentsNoCopy().add(componentClassName);
        onChanged();
    }

    void addEnabledComponent(String componentClassName, int userId) {
        modifyUserStateComponents(userId, false, true)
                .getEnabledComponentsNoCopy().add(componentClassName);
        onChanged();
    }

    boolean enableComponentLPw(String componentClassName, int userId) {
        PackageUserStateImpl state = modifyUserStateComponents(userId, false, true);
        boolean changed = state.getDisabledComponentsNoCopy() != null
                ? state.getDisabledComponentsNoCopy().remove(componentClassName) : false;
        changed |= state.getEnabledComponentsNoCopy().add(componentClassName);
        if (changed) {
            onChanged();
        }
        return changed;
    }

    boolean disableComponentLPw(String componentClassName, int userId) {
        PackageUserStateImpl state = modifyUserStateComponents(userId, true, false);
        boolean changed = state.getEnabledComponentsNoCopy() != null
                ? state.getEnabledComponentsNoCopy().remove(componentClassName) : false;
        changed |= state.getDisabledComponentsNoCopy().add(componentClassName);
        if (changed) {
            onChanged();
        }
        return changed;
    }

    boolean restoreComponentLPw(String componentClassName, int userId) {
        PackageUserStateImpl state = modifyUserStateComponents(userId, true, true);
        boolean changed = state.getDisabledComponentsNoCopy() != null
                ? state.getDisabledComponentsNoCopy().remove(componentClassName) : false;
        changed |= state.getEnabledComponentsNoCopy() != null
                ? state.getEnabledComponentsNoCopy().remove(componentClassName) : false;
        if (changed) {
            onChanged();
        }
        return changed;
    }

    int getCurrentEnabledStateLPr(String componentName, int userId) {
        PackageUserStateInternal state = readUserState(userId);
        if (state.getEnabledComponentsNoCopy() != null
                && state.getEnabledComponentsNoCopy().contains(componentName)) {
            return COMPONENT_ENABLED_STATE_ENABLED;
        } else if (state.getDisabledComponentsNoCopy() != null
                && state.getDisabledComponentsNoCopy().contains(componentName)) {
            return COMPONENT_ENABLED_STATE_DISABLED;
        } else {
            return COMPONENT_ENABLED_STATE_DEFAULT;
        }
    }

    void removeUser(int userId) {
        mUserStates.delete(userId);
        onChanged();
    }

    public int[] getNotInstalledUserIds() {
        int count = 0;
        int userStateCount = mUserStates.size();
        for (int i = 0; i < userStateCount; i++) {
            if (!mUserStates.valueAt(i).isInstalled()) {
                count++;
            }
        }
        if (count == 0) {
            return EmptyArray.INT;
        }

        int[] excludedUserIds = new int[count];
        int idx = 0;
        for (int i = 0; i < userStateCount; i++) {
            if (!mUserStates.valueAt(i).isInstalled()) {
                excludedUserIds[idx++] = mUserStates.keyAt(i);
            }
        }
        return excludedUserIds;
    }

    /**
     * TODO (b/170263003) refactor to dump to permissiongr proto Dumps the permissions that are
     * granted to users for this package.
     */
    void writePackageUserPermissionsProto(ProtoOutputStream proto, long fieldId,
            List<UserInfo> users, LegacyPermissionDataProvider dataProvider) {
        Collection<LegacyPermissionState.PermissionState> runtimePermissionStates;
        for (UserInfo user : users) {
            final long permissionsToken = proto.start(PackageProto.USER_PERMISSIONS);
            proto.write(PackageProto.UserPermissionsProto.ID, user.id);

            runtimePermissionStates = dataProvider.getLegacyPermissionState(mAppId)
                    .getPermissionStates(user.id);
            for (LegacyPermissionState.PermissionState permission : runtimePermissionStates) {
                if (permission.isGranted()) {
                    proto.write(PackageProto.UserPermissionsProto.GRANTED_PERMISSIONS,
                            permission.getName());
                }
            }
            proto.end(permissionsToken);
        }
    }

    protected void writeUsersInfoToProto(ProtoOutputStream proto, long fieldId) {
        int count = mUserStates.size();
        for (int i = 0; i < count; i++) {
            final long userToken = proto.start(fieldId);
            final int userId = mUserStates.keyAt(i);
            final PackageUserStateInternal state = mUserStates.valueAt(i);
            proto.write(PackageProto.UserInfoProto.ID, userId);
            final int installType;
            if (state.isInstantApp()) {
                installType = PackageProto.UserInfoProto.INSTANT_APP_INSTALL;
            } else if (state.isInstalled()) {
                installType = PackageProto.UserInfoProto.FULL_APP_INSTALL;
            } else {
                installType = PackageProto.UserInfoProto.NOT_INSTALLED_FOR_USER;
            }
            proto.write(PackageProto.UserInfoProto.INSTALL_TYPE, installType);
            proto.write(PackageProto.UserInfoProto.IS_HIDDEN, state.isHidden());
            proto.write(PackageProto.UserInfoProto.DISTRACTION_FLAGS, state.getDistractionFlags());
            proto.write(PackageProto.UserInfoProto.IS_SUSPENDED, state.isSuspended());
            if (state.isSuspended()) {
                for (int j = 0; j < state.getSuspendParams().size(); j++) {
                    proto.write(PackageProto.UserInfoProto.SUSPENDING_PACKAGE,
                            state.getSuspendParams().keyAt(j));
                }
            }
            proto.write(PackageProto.UserInfoProto.IS_STOPPED, state.isStopped());
            proto.write(PackageProto.UserInfoProto.IS_LAUNCHED, !state.isNotLaunched());
            proto.write(PackageProto.UserInfoProto.ENABLED_STATE, state.getEnabledState());
            proto.write(
                    PackageProto.UserInfoProto.LAST_DISABLED_APP_CALLER,
                    state.getLastDisableAppCaller());
            proto.write(PackageProto.UserInfoProto.FIRST_INSTALL_TIME_MS,
                    state.getFirstInstallTimeMillis());
            proto.end(userToken);
        }
    }

    /**
     * @see #mPath
     */
    PackageSetting setPath(@NonNull File path) {
        this.mPath = path;
        this.mPathString = path.toString();
        onChanged();
        return this;
    }

    /**
     * @param userId the specific user to change the label/icon for
     * @see PackageUserStateImpl#overrideLabelAndIcon(ComponentName, String, Integer)
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean overrideNonLocalizedLabelAndIcon(@NonNull ComponentName component,
            @Nullable String label, @Nullable Integer icon, @UserIdInt int userId) {
        boolean changed = modifyUserState(userId).overrideLabelAndIcon(component, label, icon);
        onChanged();
        return changed;
    }

    /**
     * @param userId the specific user to reset
     * @see PackageUserStateImpl#resetOverrideComponentLabelIcon()
     */
    public void resetOverrideComponentLabelIcon(@UserIdInt int userId) {
        modifyUserState(userId).resetOverrideComponentLabelIcon();
        onChanged();
    }

    /**
     * @param userId the specified user to get the theme setting from
     * @return the theme name previously persisted for the user or null if no splashscreen theme is
     * persisted.
     * @see android.window.SplashScreen#setSplashScreenTheme(int)
     */
    @Nullable
    public String getSplashScreenTheme(@UserIdInt int userId) {
        return readUserState(userId).getSplashScreenTheme();
    }

    /**
     * @return True if package is still being loaded, false if the package is fully loaded.
     */
    public boolean isLoading() {
        return Math.abs(1.0f - mLoadingProgress) >= 0.00000001f;
    }

    public PackageSetting setLoadingProgress(float progress) {
        mLoadingProgress = progress;
        onChanged();
        return this;
    }

    @NonNull
    @Override
    public long getVersionCode() {
        return versionCode;
    }

    /**
     * @see PackageState#getMimeGroups()
     */
    @Nullable
    @Override
    public Map<String, Set<String>> getMimeGroups() {
        return CollectionUtils.isEmpty(mimeGroups) ? Collections.emptyMap()
                : Collections.unmodifiableMap(mimeGroups);
    }

    @NonNull
    @Override
    public String getPackageName() {
        return mName;
    }

    @Nullable
    @Override
    public AndroidPackage getAndroidPackage() {
        return getPkg();
    }

    @NonNull
    public SigningInfo getSigningInfo() {
        return new SigningInfo(signatures.mSigningDetails);
    }

    @NonNull
    @Override
    public String[] getUsesSdkLibraries() {
        return usesSdkLibraries == null ? EmptyArray.STRING : usesSdkLibraries;
    }

    @NonNull
    @Override
    public long[] getUsesSdkLibrariesVersionsMajor() {
        return usesSdkLibrariesVersionsMajor == null ? EmptyArray.LONG
                : usesSdkLibrariesVersionsMajor;
    }

    @NonNull
    @Override
    public String[] getUsesStaticLibraries() {
        return usesStaticLibraries == null ? EmptyArray.STRING : usesStaticLibraries;
    }

    @NonNull
    @Override
    public long[] getUsesStaticLibrariesVersions() {
        return usesStaticLibrariesVersions == null ? EmptyArray.LONG : usesStaticLibrariesVersions;
    }

    @NonNull
    @Override
    public List<SharedLibrary> getUsesLibraries() {
        return (List<SharedLibrary>) (List<?>) pkgState.getUsesLibraryInfos();
    }

    @NonNull
    public PackageSetting addUsesLibraryInfo(@NonNull SharedLibraryInfo value) {
        pkgState.addUsesLibraryInfo(new SharedLibraryWrapper(value));
        return this;
    }

    @NonNull
    @Override
    public List<String> getUsesLibraryFiles() {
        return pkgState.getUsesLibraryFiles();
    }

    @NonNull
    public PackageSetting addUsesLibraryFile(String value) {
        pkgState.addUsesLibraryFile(value);
        return this;
    }

    @Override
    public boolean isHiddenUntilInstalled() {
        return pkgState.isHiddenUntilInstalled();
    }

    @NonNull
    @Override
    public long[] getLastPackageUsageTime() {
        return pkgState.getLastPackageUsageTimeInMills();
    }

    @Override
    public boolean isUpdatedSystemApp() {
        return pkgState.isUpdatedSystemApp();
    }

    @Override
    public boolean isApkInUpdatedApex() {
        return pkgState.isApkInUpdatedApex();
    }

    @Nullable
    @Override
    public String getApexModuleName() {
        return pkgState.getApexModuleName();
    }

    public PackageSetting setDomainSetId(@NonNull UUID domainSetId) {
        mDomainSetId = domainSetId;
        onChanged();
        return this;
    }

    public PackageSetting setCategoryOverride(int categoryHint) {
        this.categoryOverride = categoryHint;
        onChanged();
        return this;
    }

    public PackageSetting setLegacyNativeLibraryPath(
            String legacyNativeLibraryPathString) {
        this.legacyNativeLibraryPath = legacyNativeLibraryPathString;
        onChanged();
        return this;
    }

    public PackageSetting setMimeGroups(@NonNull Map<String, Set<String>> mimeGroups) {
        this.mimeGroups = mimeGroups;
        onChanged();
        return this;
    }

    public PackageSetting setOldCodePaths(Set<String> oldCodePaths) {
        mOldCodePaths = oldCodePaths;
        onChanged();
        return this;
    }

    public PackageSetting setUsesSdkLibraries(String[] usesSdkLibraries) {
        this.usesSdkLibraries = usesSdkLibraries;
        onChanged();
        return this;
    }

    public PackageSetting setUsesSdkLibrariesVersionsMajor(long[] usesSdkLibrariesVersions) {
        this.usesSdkLibrariesVersionsMajor = usesSdkLibrariesVersions;
        onChanged();
        return this;
    }

    public PackageSetting setUsesStaticLibraries(String[] usesStaticLibraries) {
        this.usesStaticLibraries = usesStaticLibraries;
        onChanged();
        return this;
    }

    public PackageSetting setUsesStaticLibrariesVersions(long[] usesStaticLibrariesVersions) {
        this.usesStaticLibrariesVersions = usesStaticLibrariesVersions;
        onChanged();
        return this;
    }

    public PackageSetting setApexModuleName(@Nullable String apexModuleName) {
        pkgState.setApexModuleName(apexModuleName);
        return this;
    }

    @NonNull
    @Override
    public PackageStateUnserialized getTransientState() {
        return pkgState;
    }

    @NonNull
    public SparseArray<? extends PackageUserStateInternal> getUserStates() {
        return mUserStates;
    }

    public PackageSetting addMimeTypes(String mimeGroup, Set<String> mimeTypes) {
        if (mimeGroups == null) {
            mimeGroups = new ArrayMap<>();
        }

        Set<String> existingMimeTypes = mimeGroups.get(mimeGroup);
        if (existingMimeTypes == null) {
            existingMimeTypes = new ArraySet<>();
            mimeGroups.put(mimeGroup, existingMimeTypes);
        }
        existingMimeTypes.addAll(mimeTypes);
        return this;
    }

    @NonNull
    @Override
    public PackageUserState getStateForUser(@NonNull UserHandle user) {
        PackageUserState userState = getUserStates().get(user.getIdentifier());
        return userState == null ? PackageUserState.DEFAULT : userState;
    }

    @Nullable
    public String getPrimaryCpuAbi() {
        if (TextUtils.isEmpty(mPrimaryCpuAbi) && pkg != null) {
            return AndroidPackageUtils.getRawPrimaryCpuAbi(pkg);
        }

        return mPrimaryCpuAbi;
    }

    @Nullable
    public String getSecondaryCpuAbi() {
        if (TextUtils.isEmpty(mSecondaryCpuAbi) && pkg != null) {
            return AndroidPackageUtils.getRawSecondaryCpuAbi(pkg);
        }

        return mSecondaryCpuAbi;
    }

    @Nullable
    @Override
    public String getSeInfo() {
        String overrideSeInfo = getTransientState().getOverrideSeInfo();
        if (!TextUtils.isEmpty(overrideSeInfo)) {
            return overrideSeInfo;
        }

        return getTransientState().getSeInfo();
    }

    @Nullable
    public String getPrimaryCpuAbiLegacy() {
        return mPrimaryCpuAbi;
    }

    @Nullable
    public String getSecondaryCpuAbiLegacy() {
        return mSecondaryCpuAbi;
    }

    @ApplicationInfo.HiddenApiEnforcementPolicy
    @Override
    public int getHiddenApiEnforcementPolicy() {
        return AndroidPackageUtils.getHiddenApiEnforcementPolicy(getAndroidPackage(), this);
    }

    @Override
    public boolean isApex() {
        // TODO(b/256637152):
        // TODO(b/243839669): Use a flag tracked directly in PackageSetting
        return getAndroidPackage() != null && getAndroidPackage().isApex();
    }



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/services/core/java/com/android/server/pm/PackageSetting.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    @DataClass.Generated.Member
    public @Deprecated @Nullable Set<String> getOldCodePaths() {
        return mOldCodePaths;
    }

    /**
     * The path under which native libraries have been unpacked. This path is
     * always derived at runtime, and is only stored here for cleanup when a
     * package is uninstalled.
     */
    @DataClass.Generated.Member
    public @Nullable @Deprecated String getLegacyNativeLibraryPath() {
        return legacyNativeLibraryPath;
    }

    @DataClass.Generated.Member
    public @NonNull String getName() {
        return mName;
    }

    @DataClass.Generated.Member
    public @Nullable String getRealName() {
        return mRealName;
    }

    @DataClass.Generated.Member
    public int getAppId() {
        return mAppId;
    }

    /**
     * It is expected that all code that uses a {@link PackageSetting} understands this inner field
     * may be null. Note that this relationship only works one way. It should not be possible to
     * have an entry inside {@link PackageManagerService#mPackages} without a corresponding
     * {@link PackageSetting} inside {@link Settings#mPackages}.
     *
     * @see PackageState#getAndroidPackage()
     */
    @DataClass.Generated.Member
    public @Nullable AndroidPackageInternal getPkg() {
        return pkg;
    }

    /**
     * @see AndroidPackage#getPath()
     */
    @DataClass.Generated.Member
    public @NonNull File getPath() {
        return mPath;
    }

    @DataClass.Generated.Member
    public @NonNull String getPathString() {
        return mPathString;
    }

    @DataClass.Generated.Member
    public float getLoadingProgress() {
        return mLoadingProgress;
    }

    @DataClass.Generated.Member
    public @Nullable String getCpuAbiOverride() {
        return mCpuAbiOverride;
    }

    @DataClass.Generated.Member
    public long getLastModifiedTime() {
        return mLastModifiedTime;
    }

    @DataClass.Generated.Member
    public long getLastUpdateTime() {
        return lastUpdateTime;
    }

    @DataClass.Generated.Member
    public @NonNull PackageSignatures getSignatures() {
        return signatures;
    }

    @DataClass.Generated.Member
    public boolean isInstallPermissionsFixed() {
        return installPermissionsFixed;
    }

    @DataClass.Generated.Member
    public @NonNull PackageKeySetData getKeySetData() {
        return keySetData;
    }

    @DataClass.Generated.Member
    public @NonNull InstallSource getInstallSource() {
        return installSource;
    }

    /**
     * @see PackageState#getVolumeUuid()
     */
    @DataClass.Generated.Member
    public @Nullable String getVolumeUuid() {
        return volumeUuid;
    }

    /**
     * @see PackageState#getCategoryOverride()
     */
    @DataClass.Generated.Member
    public int getCategoryOverride() {
        return categoryOverride;
    }

    /**
     * @see PackageState#isUpdateAvailable()
     */
    @DataClass.Generated.Member
    public boolean isUpdateAvailable() {
        return updateAvailable;
    }

    @DataClass.Generated.Member
    public boolean isForceQueryableOverride() {
        return forceQueryableOverride;
    }

    @DataClass.Generated.Member
    public @NonNull PackageStateUnserialized getPkgState() {
        return pkgState;
    }

    @DataClass.Generated.Member
    public @NonNull UUID getDomainSetId() {
        return mDomainSetId;
    }

    @DataClass.Generated(
            time = 1665779003744L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/services/core/java/com/android/server/pm/PackageSetting.java",
            inputSignatures = "private  int mSharedUserAppId\nprivate @android.annotation.Nullable java.util.Map<java.lang.String,java.util.Set<java.lang.String>> mimeGroups\nprivate @java.lang.Deprecated @android.annotation.Nullable java.util.Set<java.lang.String> mOldCodePaths\nprivate @android.annotation.Nullable java.lang.String[] usesSdkLibraries\nprivate @android.annotation.Nullable long[] usesSdkLibrariesVersionsMajor\nprivate @android.annotation.Nullable java.lang.String[] usesStaticLibraries\nprivate @android.annotation.Nullable long[] usesStaticLibrariesVersions\nprivate @android.annotation.Nullable @java.lang.Deprecated java.lang.String legacyNativeLibraryPath\nprivate @android.annotation.NonNull java.lang.String mName\nprivate @android.annotation.Nullable java.lang.String mRealName\nprivate  int mAppId\nprivate @android.annotation.Nullable com.android.server.pm.parsing.pkg.AndroidPackageInternal pkg\nprivate @android.annotation.NonNull java.io.File mPath\nprivate @android.annotation.NonNull java.lang.String mPathString\nprivate  float mLoadingProgress\nprivate @android.annotation.Nullable java.lang.String mPrimaryCpuAbi\nprivate @android.annotation.Nullable java.lang.String mSecondaryCpuAbi\nprivate @android.annotation.Nullable java.lang.String mCpuAbiOverride\nprivate  long mLastModifiedTime\nprivate  long lastUpdateTime\nprivate  long versionCode\nprivate @android.annotation.NonNull com.android.server.pm.PackageSignatures signatures\nprivate  boolean installPermissionsFixed\nprivate @android.annotation.NonNull com.android.server.pm.PackageKeySetData keySetData\nprivate final @android.annotation.NonNull android.util.SparseArray<com.android.server.pm.pkg.PackageUserStateImpl> mUserStates\nprivate @android.annotation.NonNull com.android.server.pm.InstallSource installSource\nprivate @android.annotation.Nullable java.lang.String volumeUuid\nprivate  int categoryOverride\nprivate  boolean updateAvailable\nprivate  boolean forceQueryableOverride\nprivate final @android.annotation.NonNull com.android.server.pm.pkg.PackageStateUnserialized pkgState\nprivate @android.annotation.NonNull java.util.UUID mDomainSetId\nprivate final @android.annotation.NonNull com.android.server.utils.SnapshotCache<com.android.server.pm.PackageSetting> mSnapshot\nprivate  com.android.server.utils.SnapshotCache<com.android.server.pm.PackageSetting> makeCache()\npublic  com.android.server.pm.PackageSetting snapshot()\npublic  void dumpDebug(android.util.proto.ProtoOutputStream,long,java.util.List<android.content.pm.UserInfo>,com.android.server.pm.permission.LegacyPermissionDataProvider)\npublic  com.android.server.pm.PackageSetting setAppId(int)\npublic  com.android.server.pm.PackageSetting setCpuAbiOverride(java.lang.String)\npublic  com.android.server.pm.PackageSetting setFirstInstallTimeFromReplaced(com.android.server.pm.pkg.PackageStateInternal,int[])\npublic  com.android.server.pm.PackageSetting setFirstInstallTime(long,int)\npublic  com.android.server.pm.PackageSetting setForceQueryableOverride(boolean)\npublic  com.android.server.pm.PackageSetting setInstallerPackageName(java.lang.String)\npublic  com.android.server.pm.PackageSetting setInstallSource(com.android.server.pm.InstallSource)\n  com.android.server.pm.PackageSetting removeInstallerPackage(java.lang.String)\npublic  com.android.server.pm.PackageSetting setIsOrphaned(boolean)\npublic  com.android.server.pm.PackageSetting setKeySetData(com.android.server.pm.PackageKeySetData)\npublic  com.android.server.pm.PackageSetting setLastModifiedTime(long)\npublic  com.android.server.pm.PackageSetting setLastUpdateTime(long)\npublic  com.android.server.pm.PackageSetting setLongVersionCode(long)\npublic  boolean setMimeGroup(java.lang.String,android.util.ArraySet<java.lang.String>)\npublic  com.android.server.pm.PackageSetting setPkg(com.android.server.pm.pkg.AndroidPackage)\npublic  com.android.server.pm.PackageSetting setPkgStateLibraryFiles(java.util.Collection<java.lang.String>)\npublic  com.android.server.pm.PackageSetting setPrimaryCpuAbi(java.lang.String)\npublic  com.android.server.pm.PackageSetting setSecondaryCpuAbi(java.lang.String)\npublic  com.android.server.pm.PackageSetting setSignatures(com.android.server.pm.PackageSignatures)\npublic  com.android.server.pm.PackageSetting setVolumeUuid(java.lang.String)\npublic @java.lang.Override boolean isExternalStorage()\npublic  com.android.server.pm.PackageSetting setUpdateAvailable(boolean)\npublic  void setSharedUserAppId(int)\npublic @java.lang.Override int getSharedUserAppId()\npublic @java.lang.Override boolean hasSharedUser()\npublic @java.lang.Override java.lang.String toString()\nprotected  void copyMimeGroups(java.util.Map<java.lang.String,java.util.Set<java.lang.String>>)\npublic  void updateFrom(com.android.server.pm.PackageSetting)\n  com.android.server.pm.PackageSetting updateMimeGroups(java.util.Set<java.lang.String>)\npublic @java.lang.Deprecated @java.lang.Override com.android.server.pm.permission.LegacyPermissionState getLegacyPermissionState()\npublic  com.android.server.pm.PackageSetting setInstallPermissionsFixed(boolean)\npublic  boolean isPrivileged()\npublic  boolean isOem()\npublic  boolean isVendor()\npublic  boolean isProduct()\npublic @java.lang.Override boolean isRequiredForSystemUser()\npublic  boolean isSystemExt()\npublic  boolean isOdm()\npublic  boolean isSystem()\npublic  android.content.pm.SigningDetails getSigningDetails()\npublic  com.android.server.pm.PackageSetting setSigningDetails(android.content.pm.SigningDetails)\npublic  void copyPackageSetting(com.android.server.pm.PackageSetting,boolean)\n @com.android.internal.annotations.VisibleForTesting com.android.server.pm.pkg.PackageUserStateImpl modifyUserState(int)\npublic  com.android.server.pm.pkg.PackageUserStateImpl getOrCreateUserState(int)\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageUserStateInternal readUserState(int)\n  void setEnabled(int,int,java.lang.String)\n  int getEnabled(int)\n  void setInstalled(boolean,int)\n  boolean getInstalled(int)\n  int getInstallReason(int)\n  void setInstallReason(int,int)\n  int getUninstallReason(int)\n  void setUninstallReason(int,int)\n @android.annotation.NonNull android.content.pm.overlay.OverlayPaths getOverlayPaths(int)\n  boolean setOverlayPathsForLibrary(java.lang.String,android.content.pm.overlay.OverlayPaths,int)\n  boolean isAnyInstalled(int[])\n  int[] queryInstalledUsers(int[],boolean)\n  long getCeDataInode(int)\n  void setCeDataInode(long,int)\n  boolean getStopped(int)\n  void setStopped(boolean,int)\n  boolean getNotLaunched(int)\n  void setNotLaunched(boolean,int)\n  boolean getHidden(int)\n  void setHidden(boolean,int)\n  int getDistractionFlags(int)\n  void setDistractionFlags(int,int)\npublic  boolean getInstantApp(int)\n  void setInstantApp(boolean,int)\n  boolean getVirtualPreload(int)\n  void setVirtualPreload(boolean,int)\n  void setUserState(int,long,int,boolean,boolean,boolean,boolean,int,android.util.ArrayMap<java.lang.String,com.android.server.pm.pkg.SuspendParams>,boolean,boolean,java.lang.String,android.util.ArraySet<java.lang.String>,android.util.ArraySet<java.lang.String>,int,int,java.lang.String,java.lang.String,long)\n  void setUserState(int,com.android.server.pm.pkg.PackageUserStateInternal)\n  com.android.server.utils.WatchedArraySet<java.lang.String> getEnabledComponents(int)\n  com.android.server.utils.WatchedArraySet<java.lang.String> getDisabledComponents(int)\n  void setEnabledComponents(com.android.server.utils.WatchedArraySet<java.lang.String>,int)\n  void setDisabledComponents(com.android.server.utils.WatchedArraySet<java.lang.String>,int)\n  void setEnabledComponentsCopy(com.android.server.utils.WatchedArraySet<java.lang.String>,int)\n  void setDisabledComponentsCopy(com.android.server.utils.WatchedArraySet<java.lang.String>,int)\n  com.android.server.pm.pkg.PackageUserStateImpl modifyUserStateComponents(int,boolean,boolean)\n  void addDisabledComponent(java.lang.String,int)\n  void addEnabledComponent(java.lang.String,int)\n  boolean enableComponentLPw(java.lang.String,int)\n  boolean disableComponentLPw(java.lang.String,int)\n  boolean restoreComponentLPw(java.lang.String,int)\n  int getCurrentEnabledStateLPr(java.lang.String,int)\n  void removeUser(int)\npublic  int[] getNotInstalledUserIds()\n  void writePackageUserPermissionsProto(android.util.proto.ProtoOutputStream,long,java.util.List<android.content.pm.UserInfo>,com.android.server.pm.permission.LegacyPermissionDataProvider)\nprotected  void writeUsersInfoToProto(android.util.proto.ProtoOutputStream,long)\n  com.android.server.pm.PackageSetting setPath(java.io.File)\npublic @com.android.internal.annotations.VisibleForTesting boolean overrideNonLocalizedLabelAndIcon(android.content.ComponentName,java.lang.String,java.lang.Integer,int)\npublic  void resetOverrideComponentLabelIcon(int)\npublic @android.annotation.Nullable java.lang.String getSplashScreenTheme(int)\npublic  boolean isLoading()\npublic  com.android.server.pm.PackageSetting setLoadingProgress(float)\npublic @android.annotation.NonNull @java.lang.Override long getVersionCode()\npublic @android.annotation.Nullable @java.lang.Override java.util.Map<java.lang.String,java.util.Set<java.lang.String>> getMimeGroups()\npublic @android.annotation.NonNull @java.lang.Override java.lang.String getPackageName()\npublic @android.annotation.Nullable @java.lang.Override com.android.server.pm.pkg.AndroidPackage getAndroidPackage()\npublic @android.annotation.NonNull android.content.pm.SigningInfo getSigningInfo()\npublic @android.annotation.NonNull @java.lang.Override java.lang.String[] getUsesSdkLibraries()\npublic @android.annotation.NonNull @java.lang.Override long[] getUsesSdkLibrariesVersionsMajor()\npublic @android.annotation.NonNull @java.lang.Override java.lang.String[] getUsesStaticLibraries()\npublic @android.annotation.NonNull @java.lang.Override long[] getUsesStaticLibrariesVersions()\npublic @android.annotation.NonNull @java.lang.Override java.util.List<com.android.server.pm.pkg.SharedLibrary> getUsesLibraries()\npublic @android.annotation.NonNull com.android.server.pm.PackageSetting addUsesLibraryInfo(android.content.pm.SharedLibraryInfo)\npublic @android.annotation.NonNull @java.lang.Override java.util.List<java.lang.String> getUsesLibraryFiles()\npublic @android.annotation.NonNull com.android.server.pm.PackageSetting addUsesLibraryFile(java.lang.String)\npublic @java.lang.Override boolean isHiddenUntilInstalled()\npublic @android.annotation.NonNull @java.lang.Override long[] getLastPackageUsageTime()\npublic @java.lang.Override boolean isUpdatedSystemApp()\npublic @java.lang.Override boolean isApkInUpdatedApex()\npublic  com.android.server.pm.PackageSetting setDomainSetId(java.util.UUID)\npublic  com.android.server.pm.PackageSetting setCategoryOverride(int)\npublic  com.android.server.pm.PackageSetting setLegacyNativeLibraryPath(java.lang.String)\npublic  com.android.server.pm.PackageSetting setMimeGroups(java.util.Map<java.lang.String,java.util.Set<java.lang.String>>)\npublic  com.android.server.pm.PackageSetting setOldCodePaths(java.util.Set<java.lang.String>)\npublic  com.android.server.pm.PackageSetting setUsesSdkLibraries(java.lang.String[])\npublic  com.android.server.pm.PackageSetting setUsesSdkLibrariesVersionsMajor(long[])\npublic  com.android.server.pm.PackageSetting setUsesStaticLibraries(java.lang.String[])\npublic  com.android.server.pm.PackageSetting setUsesStaticLibrariesVersions(long[])\npublic @android.annotation.NonNull @java.lang.Override com.android.server.pm.pkg.PackageStateUnserialized getTransientState()\npublic @android.annotation.NonNull android.util.SparseArray<? extends PackageUserStateInternal> getUserStates()\npublic  com.android.server.pm.PackageSetting addMimeTypes(java.lang.String,java.util.Set<java.lang.String>)\npublic @android.annotation.NonNull @java.lang.Override com.android.server.pm.pkg.PackageUserState getStateForUser(android.os.UserHandle)\npublic @android.annotation.Nullable java.lang.String getPrimaryCpuAbi()\npublic @android.annotation.Nullable java.lang.String getSecondaryCpuAbi()\npublic @android.annotation.Nullable @java.lang.Override java.lang.String getSeInfo()\npublic @android.annotation.Nullable java.lang.String getPrimaryCpuAbiLegacy()\npublic @android.annotation.Nullable java.lang.String getSecondaryCpuAbiLegacy()\nclass PackageSetting extends com.android.server.pm.SettingBase implements [com.android.server.pm.pkg.PackageStateInternal]\n@com.android.internal.util.DataClass(genGetters=true, genConstructor=false, genSetters=false, genBuilder=false)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
