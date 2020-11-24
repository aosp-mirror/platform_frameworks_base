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
import android.content.pm.IncrementalStatesInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageUserState;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.SigningDetails;
import android.content.pm.SigningInfo;
import android.content.pm.SuspendDialogInfo;
import android.content.pm.UserInfo;
import android.content.pm.overlay.OverlayPaths;
import android.os.PersistableBundle;
import android.os.incremental.IncrementalManager;
import android.service.pm.PackageProto;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.DataClass;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.permission.LegacyPermissionDataProvider;
import com.android.server.pm.permission.LegacyPermissionState;
import com.android.server.pm.pkg.AndroidPackageApi;
import com.android.server.pm.pkg.PackageState;
import com.android.server.pm.pkg.PackageStateUnserialized;
import com.android.server.utils.SnapshotCache;

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
import java.util.function.Predicate;
import java.util.UUID;

/**
 * Settings data for a particular package we know about.
 * @hide
 */
@DataClass(genGetters = true, genConstructor = false, genSetters = false, genBuilder = false)
@DataClass.Suppress({"getSnapshot", })
public class PackageSetting extends SettingBase implements PackageState {

    static final PackageUserState DEFAULT_USER_STATE = new PackageUserState();

    /**
     * Temporary holding space for the shared user ID. While parsing package settings, the
     * shared users tag may come after the packages. In this case, we must delay linking the
     * shared user setting with the package setting. The shared user ID lets us link the
     * two objects.
     */
    protected int sharedUserId;

    /**
     * @see PackageState#getMimeGroups()
     */
    @Nullable
    Map<String, ArraySet<String>> mimeGroups;

    /**
     * Non-persisted value. During an "upgrade without restart", we need the set
     * of all previous code paths so we can surgically add the new APKs to the
     * active classloader. If at any point an application is upgraded with a
     * restart, this field will be cleared since the classloader would be created
     * using the full set of code paths when the package's process is started.
     * TODO: Remove
     */
    @Deprecated
    @Nullable
    Set<String> mOldCodePaths;

    @NonNull
    private IncrementalStates incrementalStates;

    @Nullable
    String[] usesStaticLibraries;

    @Nullable
    long[] usesStaticLibrariesVersions;

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

    @NonNull
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
    private AndroidPackage pkg;

    /**
     * WARNING. The object reference is important. We perform integer equality and NOT
     * object equality to check whether shared user settings are the same.
     */
    @Nullable
    private SharedUserSetting sharedUser;

    /** @see AndroidPackage#getPath() */
    @NonNull
    private File mPath;
    @NonNull
    private String mPathString;

    @Nullable
    private String mPrimaryCpuAbi;

    @Nullable
    private String mSecondaryCpuAbi;

    /**
     * The install time CPU override, if any. This value is written at install time
     * and doesn't change during the life of an install. If non-null,
     * {@code primaryCpuAbiString} will contain the same value.
     */
    @Nullable
    private String mCpuAbiOverride;

    private long mLastModifiedTime;
    private long firstInstallTime;
    private long lastUpdateTime;
    private long versionCode;

    @NonNull
    private PackageSignatures signatures;

    private boolean installPermissionsFixed;

    @NonNull
    private PackageKeySetData keySetData = new PackageKeySetData();

    // TODO: Access is not locked.
    // Whether this package is currently stopped, thus can not be
    // started until explicitly launched by the user.
    @NonNull
    private final SparseArray<PackageUserState> mUserState = new SparseArray<>();

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
    private PackageStateUnserialized pkgState = new PackageStateUnserialized();

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
            int sharedUserId, String[] usesStaticLibraries,
            long[] usesStaticLibrariesVersions, Map<String, ArraySet<String>> mimeGroups,
            @NonNull UUID domainSetId) {
        super(pkgFlags, pkgPrivateFlags);
        this.mName = name;
        this.mRealName = realName;
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
        this.incrementalStates = new IncrementalStates();
        this.sharedUserId = sharedUserId;
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
        copyPackageSetting(original);
        if (sealedSnapshot) {
            sharedUser = sharedUser == null ? null : sharedUser.snapshot();
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
        proto.write(PackageProto.StatesProto.IS_LOADING, isPackageLoading());
        writeUsersInfoToProto(proto, PackageProto.USERS);
        writePackageUserPermissionsProto(proto, PackageProto.USER_PERMISSIONS, users, dataProvider);
        proto.end(packageToken);
    }

    public List<String> getMimeGroup(String mimeGroup) {
        ArraySet<String> mimeTypes = getMimeGroupInternal(mimeGroup);
        if (mimeTypes == null) {
            throw new IllegalArgumentException("Unknown MIME group " + mimeGroup
                    + " for package " + mName);
        }
        return new ArrayList<>(mimeTypes);
    }

    private ArraySet<String> getMimeGroupInternal(String mimeGroup) {
        return mimeGroups != null ? mimeGroups.get(mimeGroup) : null;
    }

    public boolean isMatch(int flags) {
        if ((flags & PackageManager.MATCH_SYSTEM_ONLY) != 0) {
            return isSystem();
        }
        return true;
    }

    public boolean isSharedUser() {
        return sharedUser != null;
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

    public PackageSetting setFirstInstallTime(long firstInstallTime) {
        this.firstInstallTime = firstInstallTime;
        onChanged();
        return this;
    }

    public PackageSetting setForceQueryableOverride(boolean forceQueryableOverride) {
        this.forceQueryableOverride = forceQueryableOverride;
        onChanged();
        return this;
    }

    public PackageSetting setInstallerPackageName(String packageName) {
        installSource = installSource.setInstallerPackage(packageName);
        onChanged();
        return this;
    }

    public PackageSetting setInstallSource(InstallSource installSource) {
        this.installSource = Objects.requireNonNull(installSource);
        onChanged();
        return this;
    }

    PackageSetting removeInstallerPackage(String packageName) {
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

    public boolean setMimeGroup(String mimeGroup, List<String> mimeTypes) {
        ArraySet<String> oldMimeTypes = getMimeGroupInternal(mimeGroup);
        if (oldMimeTypes == null) {
            throw new IllegalArgumentException("Unknown MIME group " + mimeGroup
                    + " for package " + mName);
        }

        ArraySet<String> newMimeTypes = new ArraySet<>(mimeTypes);
        boolean hasChanges = !newMimeTypes.equals(oldMimeTypes);
        mimeGroups.put(mimeGroup, newMimeTypes);
        if (hasChanges) {
            onChanged();
        }
        return hasChanges;
    }

    public PackageSetting setPkg(AndroidPackage pkg) {
        this.pkg = pkg;
        onChanged();
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
        return (pkgFlags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0;
    }

    public PackageSetting setUpdateAvailable(boolean updateAvailable) {
        this.updateAvailable = updateAvailable;
        onChanged();
        return this;
    }

    public int getSharedUserIdInt() {
        if (sharedUser != null) {
            return sharedUser.userId;
        }
        return sharedUserId;
    }

    @Override
    public String toString() {
        return "PackageSetting{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + mName + "/" + mAppId + "}";
    }

    protected void copyMimeGroups(@Nullable Map<String, ArraySet<String>> newMimeGroups) {
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

    /** Updates all fields in the current setting from another. */
    public void updateFrom(PackageSetting other) {
        copyPackageSetting(other);

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

        ArrayMap<String, ArraySet<String>> updatedMimeGroups =
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
        return (sharedUser != null)
                ? sharedUser.getLegacyPermissionState()
                : super.getLegacyPermissionState();
    }

    public PackageSetting setInstallPermissionsFixed(boolean installPermissionsFixed) {
        this.installPermissionsFixed = installPermissionsFixed;
        return this;
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

    @Override
    public boolean isRequiredForSystemUser() {
        return (pkgPrivateFlags & ApplicationInfo.PRIVATE_FLAG_REQUIRED_FOR_SYSTEM_USER) != 0;
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

    public SigningDetails getSigningDetails() {
        return signatures.mSigningDetails;
    }

    public PackageSetting setSigningDetails(SigningDetails signingDetails) {
        // TODO: Immutability
        signatures.mSigningDetails = signingDetails;
        onChanged();
        return this;
    }

    public void copyPackageSetting(PackageSetting other) {
        super.copySettingBase(other);
        sharedUserId = other.sharedUserId;
        mimeGroups = other.mimeGroups;
        incrementalStates = other.incrementalStates;
        legacyNativeLibraryPath = other.legacyNativeLibraryPath;
        mName = other.mName;
        mRealName = other.mRealName;
        mAppId = other.mAppId;
        pkg = other.pkg;
        sharedUser = other.sharedUser;
        mPath = other.mPath;
        mPathString = other.mPathString;
        mPrimaryCpuAbi = other.mPrimaryCpuAbi;
        mSecondaryCpuAbi = other.mSecondaryCpuAbi;
        mCpuAbiOverride = other.mCpuAbiOverride;
        mLastModifiedTime = other.mLastModifiedTime;
        firstInstallTime = other.firstInstallTime;
        lastUpdateTime = other.lastUpdateTime;
        versionCode = other.versionCode;
        signatures = other.signatures;
        installPermissionsFixed = other.installPermissionsFixed;
        keySetData = other.keySetData;
        installSource = other.installSource;
        volumeUuid = other.volumeUuid;
        categoryOverride = other.categoryOverride;
        updateAvailable = other.updateAvailable;
        forceQueryableOverride = other.forceQueryableOverride;
        mDomainSetId = other.mDomainSetId;

        usesStaticLibraries = other.usesStaticLibraries != null
                ? Arrays.copyOf(other.usesStaticLibraries,
                other.usesStaticLibraries.length) : null;
        usesStaticLibrariesVersions = other.usesStaticLibrariesVersions != null
                ? Arrays.copyOf(other.usesStaticLibrariesVersions,
                other.usesStaticLibrariesVersions.length) : null;

        mUserState.clear();
        for (int i = 0; i < other.mUserState.size(); i++) {
            mUserState.put(other.mUserState.keyAt(i), other.mUserState.valueAt(i));
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
    PackageUserState modifyUserState(int userId) {
        PackageUserState state = mUserState.get(userId);
        if (state == null) {
            state = new PackageUserState();
            mUserState.put(userId, state);
            onChanged();
        }
        return state;
    }

    @NonNull
    public PackageUserState readUserState(int userId) {
        PackageUserState state = mUserState.get(userId);
        if (state == null) {
            return DEFAULT_USER_STATE;
        }
        return state;
    }

    @Nullable
    public PackageUserState readUserStateNullable(int userId) {
        return mUserState.get(userId);
    }

    void setEnabled(int state, int userId, String callingPackage) {
        PackageUserState st = modifyUserState(userId);
        st.enabled = state;
        st.lastDisableAppCaller = callingPackage;
        onChanged();
    }

    int getEnabled(int userId) {
        return readUserState(userId).enabled;
    }

    String getLastDisabledAppCaller(int userId) {
        return readUserState(userId).lastDisableAppCaller;
    }

    void setInstalled(boolean inst, int userId) {
        modifyUserState(userId).installed = inst;
    }

    boolean getInstalled(int userId) {
        return readUserState(userId).installed;
    }

    int getInstallReason(int userId) {
        return readUserState(userId).installReason;
    }

    void setInstallReason(int installReason, int userId) {
        modifyUserState(userId).installReason = installReason;
    }

    int getUninstallReason(int userId) {
        return readUserState(userId).uninstallReason;
    }

    void setUninstallReason(@PackageManager.UninstallReason int uninstallReason, int userId) {
        modifyUserState(userId).uninstallReason = uninstallReason;
    }

    boolean setOverlayPaths(OverlayPaths overlayPaths, int userId) {
        return modifyUserState(userId).setOverlayPaths(overlayPaths);
    }

    @NonNull
    OverlayPaths getOverlayPaths(int userId) {
        return readUserState(userId).getOverlayPaths();
    }

    boolean setOverlayPathsForLibrary(String libName, OverlayPaths overlayPaths, int userId) {
        return modifyUserState(userId).setSharedLibraryOverlayPaths(libName, overlayPaths);
    }

    @NonNull
    Map<String, OverlayPaths> getOverlayPathsForLibrary(int userId) {
        return readUserState(userId).getSharedLibraryOverlayPaths();
    }

    /** Only use for testing. Do NOT use in production code. */
    @VisibleForTesting
    SparseArray<PackageUserState> getUserState() {
        return mUserState;
    }

    boolean isAnyInstalled(int[] users) {
        for (int user: users) {
            if (readUserState(user).installed) {
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
        return readUserState(userId).ceDataInode;
    }

    void setCeDataInode(long ceDataInode, int userId) {
        modifyUserState(userId).ceDataInode = ceDataInode;
    }

    boolean getStopped(int userId) {
        return readUserState(userId).stopped;
    }

    void setStopped(boolean stop, int userId) {
        modifyUserState(userId).stopped = stop;
    }

    boolean getNotLaunched(int userId) {
        return readUserState(userId).notLaunched;
    }

    void setNotLaunched(boolean stop, int userId) {
        modifyUserState(userId).notLaunched = stop;
    }

    boolean getHidden(int userId) {
        return readUserState(userId).hidden;
    }

    void setHidden(boolean hidden, int userId) {
        modifyUserState(userId).hidden = hidden;
    }

    int getDistractionFlags(int userId) {
        return readUserState(userId).distractionFlags;
    }

    void setDistractionFlags(int distractionFlags, int userId) {
        modifyUserState(userId).distractionFlags = distractionFlags;
    }

    boolean getSuspended(int userId) {
        return readUserState(userId).suspended;
    }

    boolean isSuspendedBy(String suspendingPackage, int userId) {
        final PackageUserState state = readUserState(userId);
        return state.suspendParams != null && state.suspendParams.containsKey(suspendingPackage);
    }

    boolean addOrUpdateSuspension(String suspendingPackage, SuspendDialogInfo dialogInfo,
            PersistableBundle appExtras, PersistableBundle launcherExtras, int userId) {
        final PackageUserState existingUserState = modifyUserState(userId);
        final PackageUserState.SuspendParams newSuspendParams =
                PackageUserState.SuspendParams.getInstanceOrNull(dialogInfo, appExtras,
                        launcherExtras);
        if (existingUserState.suspendParams == null) {
            existingUserState.suspendParams = new ArrayMap<>();
        }
        final PackageUserState.SuspendParams oldSuspendParams =
                existingUserState.suspendParams.put(suspendingPackage, newSuspendParams);
        existingUserState.suspended = true;
        onChanged();
        return !Objects.equals(oldSuspendParams, newSuspendParams);
    }

    boolean removeSuspension(String suspendingPackage, int userId) {
        boolean wasModified = false;
        final PackageUserState existingUserState = modifyUserState(userId);
        if (existingUserState.suspendParams != null) {
            if (existingUserState.suspendParams.remove(suspendingPackage) != null) {
                wasModified = true;
            }
            if (existingUserState.suspendParams.size() == 0) {
                existingUserState.suspendParams = null;
            }
        }
        existingUserState.suspended = (existingUserState.suspendParams != null);
        onChanged();
        return wasModified;
    }

    void removeSuspension(Predicate<String> suspendingPackagePredicate, int userId) {
        final PackageUserState existingUserState = modifyUserState(userId);
        if (existingUserState.suspendParams != null) {
            for (int i = existingUserState.suspendParams.size() - 1; i >= 0; i--) {
                final String suspendingPackage = existingUserState.suspendParams.keyAt(i);
                if (suspendingPackagePredicate.test(suspendingPackage)) {
                    existingUserState.suspendParams.removeAt(i);
                }
            }
            if (existingUserState.suspendParams.size() == 0) {
                existingUserState.suspendParams = null;
            }
        }
        existingUserState.suspended = (existingUserState.suspendParams != null);
        onChanged();
    }

    public boolean getInstantApp(int userId) {
        return readUserState(userId).instantApp;
    }

    void setInstantApp(boolean instantApp, int userId) {
        modifyUserState(userId).instantApp = instantApp;
    }

    boolean getVirtualPreload(int userId) {
        return readUserState(userId).virtualPreload;
    }

    void setVirtualPreload(boolean virtualPreload, int userId) {
        modifyUserState(userId).virtualPreload = virtualPreload;
    }

    void setUserState(int userId, long ceDataInode, int enabled, boolean installed, boolean stopped,
            boolean notLaunched, boolean hidden, int distractionFlags, boolean suspended,
            ArrayMap<String, PackageUserState.SuspendParams> suspendParams, boolean instantApp,
            boolean virtualPreload, String lastDisableAppCaller,
            ArraySet<String> enabledComponents, ArraySet<String> disabledComponents,
            int installReason, int uninstallReason,
            String harmfulAppWarning, String splashScreenTheme) {
        PackageUserState state = modifyUserState(userId);
        state.ceDataInode = ceDataInode;
        state.enabled = enabled;
        state.installed = installed;
        state.stopped = stopped;
        state.notLaunched = notLaunched;
        state.hidden = hidden;
        state.distractionFlags = distractionFlags;
        state.suspended = suspended;
        state.suspendParams = suspendParams;
        state.lastDisableAppCaller = lastDisableAppCaller;
        state.enabledComponents = enabledComponents;
        state.disabledComponents = disabledComponents;
        state.installReason = installReason;
        state.uninstallReason = uninstallReason;
        state.instantApp = instantApp;
        state.virtualPreload = virtualPreload;
        state.harmfulAppWarning = harmfulAppWarning;
        state.splashScreenTheme = splashScreenTheme;
        onChanged();
    }

    void setUserState(int userId, PackageUserState otherState) {
        setUserState(userId, otherState.ceDataInode, otherState.enabled, otherState.installed,
                otherState.stopped, otherState.notLaunched, otherState.hidden,
                otherState.distractionFlags, otherState.suspended, otherState.suspendParams,
                otherState.instantApp,
                otherState.virtualPreload, otherState.lastDisableAppCaller,
                otherState.enabledComponents, otherState.disabledComponents,
                otherState.installReason, otherState.uninstallReason, otherState.harmfulAppWarning,
                otherState.splashScreenTheme);
    }

    ArraySet<String> getEnabledComponents(int userId) {
        return readUserState(userId).enabledComponents;
    }

    ArraySet<String> getDisabledComponents(int userId) {
        return readUserState(userId).disabledComponents;
    }

    void setEnabledComponents(ArraySet<String> components, int userId) {
        modifyUserState(userId).enabledComponents = components;
    }

    void setDisabledComponents(ArraySet<String> components, int userId) {
        modifyUserState(userId).disabledComponents = components;
    }

    void setEnabledComponentsCopy(ArraySet<String> components, int userId) {
        modifyUserState(userId).enabledComponents = components != null
                ? new ArraySet<String>(components) : null;
    }

    void setDisabledComponentsCopy(ArraySet<String> components, int userId) {
        modifyUserState(userId).disabledComponents = components != null
                ? new ArraySet<String>(components) : null;
    }

    PackageUserState modifyUserStateComponents(int userId, boolean disabled, boolean enabled) {
        PackageUserState state = modifyUserState(userId);
        boolean changed = false;
        if (disabled && state.disabledComponents == null) {
            state.disabledComponents = new ArraySet<String>(1);
            changed = true;
        }
        if (enabled && state.enabledComponents == null) {
            state.enabledComponents = new ArraySet<String>(1);
            changed = true;
        }
        if (changed) {
            onChanged();
        }
        return state;
    }

    void addDisabledComponent(String componentClassName, int userId) {
        modifyUserStateComponents(userId, true, false).disabledComponents.add(componentClassName);
    }

    void addEnabledComponent(String componentClassName, int userId) {
        modifyUserStateComponents(userId, false, true).enabledComponents.add(componentClassName);
    }

    boolean enableComponentLPw(String componentClassName, int userId) {
        PackageUserState state = modifyUserStateComponents(userId, false, true);
        boolean changed = state.disabledComponents != null
                ? state.disabledComponents.remove(componentClassName) : false;
        changed |= state.enabledComponents.add(componentClassName);
        return changed;
    }

    boolean disableComponentLPw(String componentClassName, int userId) {
        PackageUserState state = modifyUserStateComponents(userId, true, false);
        boolean changed = state.enabledComponents != null
                ? state.enabledComponents.remove(componentClassName) : false;
        changed |= state.disabledComponents.add(componentClassName);
        return changed;
    }

    boolean restoreComponentLPw(String componentClassName, int userId) {
        PackageUserState state = modifyUserStateComponents(userId, true, true);
        boolean changed = state.disabledComponents != null
                ? state.disabledComponents.remove(componentClassName) : false;
        changed |= state.enabledComponents != null
                ? state.enabledComponents.remove(componentClassName) : false;
        return changed;
    }

    int getCurrentEnabledStateLPr(String componentName, int userId) {
        PackageUserState state = readUserState(userId);
        if (state.enabledComponents != null && state.enabledComponents.contains(componentName)) {
            return COMPONENT_ENABLED_STATE_ENABLED;
        } else if (state.disabledComponents != null
                && state.disabledComponents.contains(componentName)) {
            return COMPONENT_ENABLED_STATE_DISABLED;
        } else {
            return COMPONENT_ENABLED_STATE_DEFAULT;
        }
    }

    void removeUser(int userId) {
        mUserState.delete(userId);
        onChanged();
    }

    public int[] getNotInstalledUserIds() {
        int count = 0;
        int userStateCount = mUserState.size();
        for (int i = 0; i < userStateCount; i++) {
            if (!mUserState.valueAt(i).installed) {
                count++;
            }
        }
        if (count == 0) {
            return EmptyArray.INT;
        }

        int[] excludedUserIds = new int[count];
        int idx = 0;
        for (int i = 0; i < userStateCount; i++) {
            if (!mUserState.valueAt(i).installed) {
                excludedUserIds[idx++] = mUserState.keyAt(i);
            }
        }
        return excludedUserIds;
    }

    /**
     * TODO (b/170263003) refactor to dump to permissiongr proto
     * Dumps the permissions that are granted to users for this package.
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
        int count = mUserState.size();
        for (int i = 0; i < count; i++) {
            final long userToken = proto.start(fieldId);
            final int userId = mUserState.keyAt(i);
            final PackageUserState state = mUserState.valueAt(i);
            proto.write(PackageProto.UserInfoProto.ID, userId);
            final int installType;
            if (state.instantApp) {
                installType = PackageProto.UserInfoProto.INSTANT_APP_INSTALL;
            } else if (state.installed) {
                installType = PackageProto.UserInfoProto.FULL_APP_INSTALL;
            } else {
                installType = PackageProto.UserInfoProto.NOT_INSTALLED_FOR_USER;
            }
            proto.write(PackageProto.UserInfoProto.INSTALL_TYPE, installType);
            proto.write(PackageProto.UserInfoProto.IS_HIDDEN, state.hidden);
            proto.write(PackageProto.UserInfoProto.DISTRACTION_FLAGS, state.distractionFlags);
            proto.write(PackageProto.UserInfoProto.IS_SUSPENDED, state.suspended);
            if (state.suspended) {
                for (int j = 0; j < state.suspendParams.size(); j++) {
                    proto.write(PackageProto.UserInfoProto.SUSPENDING_PACKAGE,
                            state.suspendParams.keyAt(j));
                }
            }
            proto.write(PackageProto.UserInfoProto.IS_STOPPED, state.stopped);
            proto.write(PackageProto.UserInfoProto.IS_LAUNCHED, !state.notLaunched);
            proto.write(PackageProto.UserInfoProto.ENABLED_STATE, state.enabled);
            proto.write(
                    PackageProto.UserInfoProto.LAST_DISABLED_APP_CALLER,
                    state.lastDisableAppCaller);
            proto.end(userToken);
        }
    }

    void setHarmfulAppWarning(int userId, String harmfulAppWarning) {
        PackageUserState userState = modifyUserState(userId);
        userState.harmfulAppWarning = harmfulAppWarning;
        onChanged();
    }

    String getHarmfulAppWarning(int userId) {
        PackageUserState userState = readUserState(userId);
        return userState.harmfulAppWarning;
    }

    /**
     * @see #mPath
     */
    PackageSetting setPath(@NonNull File path) {
        this.mPath = path;
        this.mPathString = path.toString();
        return this;
    }

    /** @see #mPath */
    String getPathString() {
        return mPathString;
    }

    /**
     * @see PackageUserState#overrideLabelAndIcon(ComponentName, String, Integer)
     *
     * @param userId the specific user to change the label/icon for
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean overrideNonLocalizedLabelAndIcon(@NonNull ComponentName component,
            @Nullable String label, @Nullable Integer icon, @UserIdInt int userId) {
        return modifyUserState(userId).overrideLabelAndIcon(component, label, icon);
    }

    /**
     * @see PackageUserState#resetOverrideComponentLabelIcon()
     *
     * @param userId the specific user to reset
     */
    public void resetOverrideComponentLabelIcon(@UserIdInt int userId) {
        modifyUserState(userId).resetOverrideComponentLabelIcon();
    }

    /**
     * @param userId    the specified user to modify the theme for
     * @param themeName the theme name to persist
     * @see android.window.SplashScreen#setSplashScreenTheme(int)
     */
    public void setSplashScreenTheme(@UserIdInt int userId, @Nullable String themeName) {
        modifyUserState(userId).splashScreenTheme = themeName;
        onChanged();
    }

    /**
     * @param userId the specified user to get the theme setting from
     * @return the theme name previously persisted for the user or null
     * if no splashscreen theme is persisted.
     * @see android.window.SplashScreen#setSplashScreenTheme(int)
     */
    @Nullable
    public String getSplashScreenTheme(@UserIdInt int userId) {
        return readUserState(userId).splashScreenTheme;
    }

    /**
     * @return True if package is still being loaded, false if the package is fully loaded.
     */
    public boolean isPackageLoading() {
        return incrementalStates.getIncrementalStatesInfo().isLoading();
    }

    /**
     * @return all current states in a Parcelable.
     */
    public IncrementalStatesInfo getIncrementalStatesInfo() {
        return incrementalStates.getIncrementalStatesInfo();
    }

    /**
     * Called to indicate that the package installation has been committed. This will create a
     * new startable state and a new loading state with default values. By default, the package is
     * startable after commit. For a package installed on Incremental, the loading state is true.
     * For non-Incremental packages, the loading state is false.
     */
    public void setStatesOnCommit() {
        incrementalStates.onCommit(IncrementalManager.isIncrementalPath(getPathString()));
    }

    /**
     * Called to set the callback to listen for startable state changes.
     */
    public void setIncrementalStatesCallback(IncrementalStates.Callback callback) {
        incrementalStates.setCallback(callback);
    }

    /**
     * Called to report progress changes. This might trigger loading state change.
     * @see IncrementalStates#setProgress(float)
     */
    public void setLoadingProgress(float progress) {
        incrementalStates.setProgress(progress);
    }

    @NonNull
    @Override
    public long getLongVersionCode() {
        return versionCode;
    }

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
    public AndroidPackageApi getAndroidPackage() {
        return getPkg();
    }

    @Nullable
    @Override
    public Integer getSharedUserId() {
        return sharedUser == null ? null : sharedUser.userId;
    }

    @NonNull
    public SigningInfo getSigningInfo() {
        return new SigningInfo(signatures.mSigningDetails);
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
    public List<SharedLibraryInfo> getUsesLibraryInfos() {
        return pkgState.getUsesLibraryInfos();
    }

    @NonNull
    @Override
    public List<String> getUsesLibraryFiles() {
        return pkgState.getUsesLibraryFiles();
    }

    @Override
    public boolean isHiddenUntilInstalled() {
        return pkgState.isHiddenUntilInstalled();
    }

    @Nullable
    @Override
    public String getSeInfoOverride() {
        return pkgState.getOverrideSeInfo();
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

    @Deprecated
    @Override
    public int[] getUserIds() {
        int size = mUserState.size();
        int[] array = new int[size];
        for (int index = 0; index < size; index++) {
            array[index] = mUserState.keyAt(index);
        }
        return array;
    }

    @Override
    public PackageUserState getUserState(int userId) {
        return readUserStateNullable(userId);
    }

    public PackageSetting setDomainSetId(@NonNull UUID domainSetId) {
        mDomainSetId = domainSetId;
        onChanged();
        return this;
    }

    public PackageSetting setSharedUser(SharedUserSetting sharedUser) {
        this.sharedUser = sharedUser;
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

    public PackageSetting setIncrementalStates(
            IncrementalStates incrementalStates) {
        this.incrementalStates = incrementalStates;
        onChanged();
        return this;
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


    /**
     * Non-persisted value. During an "upgrade without restart", we need the set
     * of all previous code paths so we can surgically add the new APKs to the
     * active classloader. If at any point an application is upgraded with a
     * restart, this field will be cleared since the classloader would be created
     * using the full set of code paths when the package's process is started.
     * TODO: Remove
     */
    @DataClass.Generated.Member
    public @Deprecated @Nullable Set<String> getOldCodePaths() {
        return mOldCodePaths;
    }

    @DataClass.Generated.Member
    public @NonNull IncrementalStates getIncrementalStates() {
        return incrementalStates;
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
    public @NonNull String getRealName() {
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
    public @Nullable AndroidPackage getPkg() {
        return pkg;
    }

    /**
     * WARNING. The object reference is important. We perform integer equality and NOT
     * object equality to check whether shared user settings are the same.
     */
    @DataClass.Generated.Member
    public @Nullable SharedUserSetting getSharedUser() {
        return sharedUser;
    }

    /**
     * @see AndroidPackage#getPath()
     */
    @DataClass.Generated.Member
    public @NonNull File getPath() {
        return mPath;
    }

    @DataClass.Generated.Member
    public @Nullable String getPrimaryCpuAbi() {
        return mPrimaryCpuAbi;
    }

    @DataClass.Generated.Member
    public @Nullable String getSecondaryCpuAbi() {
        return mSecondaryCpuAbi;
    }

    /**
     * The install time CPU override, if any. This value is written at install time
     * and doesn't change during the life of an install. If non-null,
     * {@code primaryCpuAbiString} will contain the same value.
     */
    @DataClass.Generated.Member
    public @Nullable String getCpuAbiOverride() {
        return mCpuAbiOverride;
    }

    @DataClass.Generated.Member
    public long getLastModifiedTime() {
        return mLastModifiedTime;
    }

    @DataClass.Generated.Member
    public long getFirstInstallTime() {
        return firstInstallTime;
    }

    @DataClass.Generated.Member
    public long getLastUpdateTime() {
        return lastUpdateTime;
    }

    @DataClass.Generated.Member
    public long getVersionCode() {
        return versionCode;
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
            time = 1628017546382L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/services/core/java/com/android/server/pm/PackageSetting.java",
            inputSignatures = "static final  android.content.pm.PackageUserState DEFAULT_USER_STATE\nprotected  int sharedUserId\n @android.annotation.Nullable java.util.Map<java.lang.String,android.util.ArraySet<java.lang.String>> mimeGroups\n @java.lang.Deprecated @android.annotation.Nullable java.util.Set<java.lang.String> mOldCodePaths\nprivate @android.annotation.NonNull com.android.server.pm.IncrementalStates incrementalStates\n @android.annotation.Nullable java.lang.String[] usesStaticLibraries\n @android.annotation.Nullable long[] usesStaticLibrariesVersions\nprivate @android.annotation.Nullable @java.lang.Deprecated java.lang.String legacyNativeLibraryPath\nprivate @android.annotation.NonNull java.lang.String mName\nprivate @android.annotation.NonNull java.lang.String mRealName\nprivate  int mAppId\nprivate @android.annotation.Nullable com.android.server.pm.parsing.pkg.AndroidPackage pkg\nprivate @android.annotation.Nullable com.android.server.pm.SharedUserSetting sharedUser\nprivate @android.annotation.NonNull java.io.File mPath\nprivate @android.annotation.NonNull java.lang.String mPathString\nprivate @android.annotation.Nullable java.lang.String mPrimaryCpuAbi\nprivate @android.annotation.Nullable java.lang.String mSecondaryCpuAbi\nprivate @android.annotation.Nullable java.lang.String mCpuAbiOverride\nprivate  long mLastModifiedTime\nprivate  long firstInstallTime\nprivate  long lastUpdateTime\nprivate  long versionCode\nprivate @android.annotation.NonNull com.android.server.pm.PackageSignatures signatures\nprivate  boolean installPermissionsFixed\nprivate @android.annotation.NonNull com.android.server.pm.PackageKeySetData keySetData\nprivate final @android.annotation.NonNull android.util.SparseArray<android.content.pm.PackageUserState> mUserState\nprivate @android.annotation.NonNull com.android.server.pm.InstallSource installSource\nprivate @android.annotation.Nullable java.lang.String volumeUuid\nprivate  int categoryOverride\nprivate  boolean updateAvailable\nprivate  boolean forceQueryableOverride\nprivate @android.annotation.NonNull com.android.server.pm.pkg.PackageStateUnserialized pkgState\nprivate @android.annotation.NonNull java.util.UUID mDomainSetId\nprivate final @android.annotation.NonNull com.android.server.utils.SnapshotCache<com.android.server.pm.PackageSetting> mSnapshot\nprivate  com.android.server.utils.SnapshotCache<com.android.server.pm.PackageSetting> makeCache()\npublic  com.android.server.pm.PackageSetting snapshot()\npublic  void dumpDebug(android.util.proto.ProtoOutputStream,long,java.util.List<android.content.pm.UserInfo>,com.android.server.pm.permission.LegacyPermissionDataProvider)\npublic  java.util.List<java.lang.String> getMimeGroup(java.lang.String)\nprivate  android.util.ArraySet<java.lang.String> getMimeGroupInternal(java.lang.String)\npublic  boolean isMatch(int)\npublic  boolean isSharedUser()\npublic  com.android.server.pm.PackageSetting setAppId(int)\npublic  com.android.server.pm.PackageSetting setCpuAbiOverride(java.lang.String)\npublic  com.android.server.pm.PackageSetting setFirstInstallTime(long)\npublic  com.android.server.pm.PackageSetting setForceQueryableOverride(boolean)\npublic  com.android.server.pm.PackageSetting setInstallerPackageName(java.lang.String)\npublic  com.android.server.pm.PackageSetting setInstallSource(com.android.server.pm.InstallSource)\n  com.android.server.pm.PackageSetting removeInstallerPackage(java.lang.String)\npublic  com.android.server.pm.PackageSetting setIsOrphaned(boolean)\npublic  com.android.server.pm.PackageSetting setKeySetData(com.android.server.pm.PackageKeySetData)\npublic  com.android.server.pm.PackageSetting setLastModifiedTime(long)\npublic  com.android.server.pm.PackageSetting setLastUpdateTime(long)\npublic  com.android.server.pm.PackageSetting setLongVersionCode(long)\npublic  boolean setMimeGroup(java.lang.String,java.util.List<java.lang.String>)\npublic  com.android.server.pm.PackageSetting setPkg(com.android.server.pm.parsing.pkg.AndroidPackage)\npublic  com.android.server.pm.PackageSetting setPrimaryCpuAbi(java.lang.String)\npublic  com.android.server.pm.PackageSetting setSecondaryCpuAbi(java.lang.String)\npublic  com.android.server.pm.PackageSetting setSignatures(com.android.server.pm.PackageSignatures)\npublic  com.android.server.pm.PackageSetting setVolumeUuid(java.lang.String)\npublic @java.lang.Override boolean isExternalStorage()\npublic  com.android.server.pm.PackageSetting setUpdateAvailable(boolean)\npublic  int getSharedUserIdInt()\npublic @java.lang.Override java.lang.String toString()\nprotected  void copyMimeGroups(java.util.Map<java.lang.String,android.util.ArraySet<java.lang.String>>)\npublic  void updateFrom(com.android.server.pm.PackageSetting)\n  com.android.server.pm.PackageSetting updateMimeGroups(java.util.Set<java.lang.String>)\npublic @java.lang.Deprecated @java.lang.Override com.android.server.pm.permission.LegacyPermissionState getLegacyPermissionState()\npublic  com.android.server.pm.PackageSetting setInstallPermissionsFixed(boolean)\npublic  boolean isPrivileged()\npublic  boolean isOem()\npublic  boolean isVendor()\npublic  boolean isProduct()\npublic @java.lang.Override boolean isRequiredForSystemUser()\npublic  boolean isSystemExt()\npublic  boolean isOdm()\npublic  boolean isSystem()\npublic  android.content.pm.SigningDetails getSigningDetails()\npublic  com.android.server.pm.PackageSetting setSigningDetails(android.content.pm.SigningDetails)\npublic  void copyFrom(com.android.server.pm.PackageSetting)\nprivate  void doCopy(com.android.server.pm.PackageSetting)\n @com.android.internal.annotations.VisibleForTesting android.content.pm.PackageUserState modifyUserState(int)\npublic @android.annotation.NonNull android.content.pm.PackageUserState readUserState(int)\npublic @android.annotation.Nullable android.content.pm.PackageUserState readUserStateNullable(int)\n  void setEnabled(int,int,java.lang.String)\n  int getEnabled(int)\n  java.lang.String getLastDisabledAppCaller(int)\n  void setInstalled(boolean,int)\n  boolean getInstalled(int)\n  int getInstallReason(int)\n  void setInstallReason(int,int)\n  int getUninstallReason(int)\n  void setUninstallReason(int,int)\n  boolean setOverlayPaths(android.content.pm.overlay.OverlayPaths,int)\n @android.annotation.NonNull android.content.pm.overlay.OverlayPaths getOverlayPaths(int)\n  boolean setOverlayPathsForLibrary(java.lang.String,android.content.pm.overlay.OverlayPaths,int)\n @android.annotation.NonNull java.util.Map<java.lang.String,android.content.pm.overlay.OverlayPaths> getOverlayPathsForLibrary(int)\n @com.android.internal.annotations.VisibleForTesting android.util.SparseArray<android.content.pm.PackageUserState> getUserState()\n  boolean isAnyInstalled(int[])\n  int[] queryInstalledUsers(int[],boolean)\n  long getCeDataInode(int)\n  void setCeDataInode(long,int)\n  boolean getStopped(int)\n  void setStopped(boolean,int)\n  boolean getNotLaunched(int)\n  void setNotLaunched(boolean,int)\n  boolean getHidden(int)\n  void setHidden(boolean,int)\n  int getDistractionFlags(int)\n  void setDistractionFlags(int,int)\n  boolean getSuspended(int)\n  boolean isSuspendedBy(java.lang.String,int)\n  void addOrUpdateSuspension(java.lang.String,android.content.pm.SuspendDialogInfo,android.os.PersistableBundle,android.os.PersistableBundle,int)\n  void removeSuspension(java.lang.String,int)\n  void removeSuspension(java.util.function.Predicate<java.lang.String>,int)\npublic  boolean getInstantApp(int)\n  void setInstantApp(boolean,int)\n  boolean getVirtualPreload(int)\n  void setVirtualPreload(boolean,int)\n  void setUserState(int,long,int,boolean,boolean,boolean,boolean,int,boolean,android.util.ArrayMap<java.lang.String,android.content.pm.PackageUserState.SuspendParams>,boolean,boolean,java.lang.String,android.util.ArraySet<java.lang.String>,android.util.ArraySet<java.lang.String>,int,int,java.lang.String,java.lang.String)\n  void setUserState(int,android.content.pm.PackageUserState)\n  android.util.ArraySet<java.lang.String> getEnabledComponents(int)\n  android.util.ArraySet<java.lang.String> getDisabledComponents(int)\n  void setEnabledComponents(android.util.ArraySet<java.lang.String>,int)\n  void setDisabledComponents(android.util.ArraySet<java.lang.String>,int)\n  void setEnabledComponentsCopy(android.util.ArraySet<java.lang.String>,int)\n  void setDisabledComponentsCopy(android.util.ArraySet<java.lang.String>,int)\n  android.content.pm.PackageUserState modifyUserStateComponents(int,boolean,boolean)\n  void addDisabledComponent(java.lang.String,int)\n  void addEnabledComponent(java.lang.String,int)\n  boolean enableComponentLPw(java.lang.String,int)\n  boolean disableComponentLPw(java.lang.String,int)\n  boolean restoreComponentLPw(java.lang.String,int)\n  int getCurrentEnabledStateLPr(java.lang.String,int)\n  void removeUser(int)\npublic  int[] getNotInstalledUserIds()\n  void writePackageUserPermissionsProto(android.util.proto.ProtoOutputStream,long,java.util.List<android.content.pm.UserInfo>,com.android.server.pm.permission.LegacyPermissionDataProvider)\nprotected  void writeUsersInfoToProto(android.util.proto.ProtoOutputStream,long)\n  void setHarmfulAppWarning(int,java.lang.String)\n  java.lang.String getHarmfulAppWarning(int)\n  com.android.server.pm.PackageSetting setPath(java.io.File)\n  java.lang.String getPathString()\npublic @com.android.internal.annotations.VisibleForTesting boolean overrideNonLocalizedLabelAndIcon(android.content.ComponentName,java.lang.String,java.lang.Integer,int)\npublic  void resetOverrideComponentLabelIcon(int)\npublic  void setSplashScreenTheme(int,java.lang.String)\npublic @android.annotation.Nullable java.lang.String getSplashScreenTheme(int)\npublic  boolean isPackageLoading()\npublic  android.content.pm.IncrementalStatesInfo getIncrementalStatesInfo()\npublic  void setStatesOnCommit()\npublic  void setIncrementalStatesCallback(com.android.server.pm.IncrementalStates.Callback)\npublic  void setLoadingProgress(float)\npublic @android.annotation.NonNull @java.lang.Override long getLongVersionCode()\npublic @android.annotation.Nullable @java.lang.Override java.util.Map<java.lang.String,java.util.Set<java.lang.String>> getMimeGroups()\npublic @android.annotation.NonNull @java.lang.Override java.lang.String getPackageName()\npublic @android.annotation.Nullable @java.lang.Override com.android.server.pm.pkg.AndroidPackageApi getAndroidPackage()\npublic @android.annotation.Nullable @java.lang.Override java.lang.Integer getSharedUserId()\npublic @android.annotation.NonNull android.content.pm.SigningInfo getSigningInfo()\npublic @android.annotation.NonNull @java.lang.Override java.lang.String[] getUsesStaticLibraries()\npublic @android.annotation.NonNull @java.lang.Override long[] getUsesStaticLibrariesVersions()\npublic @android.annotation.NonNull @java.lang.Override java.util.List<android.content.pm.SharedLibraryInfo> getUsesLibraryInfos()\npublic @android.annotation.NonNull @java.lang.Override java.util.List<java.lang.String> getUsesLibraryFiles()\npublic @java.lang.Override boolean isHiddenUntilInstalled()\npublic @android.annotation.Nullable @java.lang.Override java.lang.String getSeInfoOverride()\npublic @android.annotation.NonNull @java.lang.Override long[] getLastPackageUsageTime()\npublic @java.lang.Override boolean isUpdatedSystemApp()\npublic @java.lang.Deprecated @java.lang.Override int[] getUserIds()\npublic @java.lang.Override android.content.pm.PackageUserState getUserState(int)\npublic  com.android.server.pm.PackageSetting setDomainSetId(java.util.UUID)\npublic  com.android.server.pm.PackageSetting setSharedUser(com.android.server.pm.SharedUserSetting)\npublic  com.android.server.pm.PackageSetting setCategoryOverride(int)\npublic  com.android.server.pm.PackageSetting setLegacyNativeLibraryPath(java.lang.String)\npublic  com.android.server.pm.PackageSetting setIncrementalStates(com.android.server.pm.IncrementalStates)\nclass PackageSetting extends com.android.server.pm.SettingBase implements [com.android.server.pm.pkg.PackageState]\n@com.android.internal.util.DataClass(genGetters=true, genConstructor=false, genSetters=false, genBuilder=false)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
