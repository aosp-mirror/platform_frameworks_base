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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageParser;
import android.content.pm.parsing.ParsingPackage;
import android.content.pm.parsing.ParsingPackageImpl;
import android.content.pm.parsing.component.ParsedActivity;
import android.content.pm.parsing.component.ParsedProvider;
import android.content.pm.parsing.component.ParsedService;
import android.content.res.TypedArray;
import android.os.Environment;
import android.os.Parcel;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.DataClass;
import com.android.internal.util.Parcelling.BuiltIn.ForInternedString;
import com.android.server.pm.parsing.PackageInfoUtils;

import java.io.File;
import java.util.UUID;

/**
 * Extensions to {@link ParsingPackageImpl} including fields/state contained in the system server
 * and not exposed to the core SDK.
 *
 * Many of the fields contained here will eventually be moved inside
 * {@link com.android.server.pm.PackageSetting} or {@link android.content.pm.PackageUserState}.
 *
 * @hide
 */
public final class PackageImpl extends ParsingPackageImpl implements ParsedPackage, AndroidPackage {

    public static PackageImpl forParsing(@NonNull String packageName, @NonNull String baseCodePath,
            @NonNull String codePath, @NonNull TypedArray manifestArray, boolean isCoreApp) {
        return new PackageImpl(packageName, baseCodePath, codePath, manifestArray, isCoreApp);
    }

    /**
     * Mock an unavailable {@link AndroidPackage} to use when
     * removing
     * a package from the system.
     * This can occur if the package was installed on a storage device that has since been removed.
     * Since the infrastructure uses {@link AndroidPackage}, but
     * for
     * this case only cares about
     * volumeUuid, just fake it rather than having separate method paths.
     */
    public static AndroidPackage buildFakeForDeletion(String packageName, String volumeUuid) {
        return ((ParsedPackage) PackageImpl.forTesting(packageName)
                .setVolumeUuid(volumeUuid)
                .hideAsParsed())
                .hideAsFinal();
    }

    @VisibleForTesting
    public static ParsingPackage forTesting(String packageName) {
        return forTesting(packageName, "");
    }

    @VisibleForTesting
    public static ParsingPackage forTesting(String packageName, String baseCodePath) {
        return new PackageImpl(packageName, baseCodePath, baseCodePath, null, false);
    }

    @NonNull
    @DataClass.ParcelWith(ForInternedString.class)
    private final String manifestPackageName;

    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    protected String nativeLibraryDir;

    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    protected String nativeLibraryRootDir;

    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    protected String primaryCpuAbi;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    protected String secondaryCpuAbi;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    protected String secondaryNativeLibraryDir;

    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    protected String seInfo;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    protected String seInfoUser;

    /**
     * This is an appId, the uid if the userId is == USER_SYSTEM
     */
    private int uid = -1;

    // This is kept around as a boolean to avoid flag calculation
    // during ApplicationInfo generation.
    private boolean nativeLibraryRootRequiresIsa;

    private int mBooleans;

    /**
     * @see ParsingPackageImpl.Booleans
     */
    private static class Booleans {
        @IntDef({
                CORE_APP,
                SYSTEM,
                FACTORY_TEST,
                SYSTEM_EXT,
                PRIVILEGED,
                OEM,
                VENDOR,
                PRODUCT,
                ODM,
                SIGNED_WITH_PLATFORM_KEY,
                NATIVE_LIBRARY_ROOT_REQUIRES_ISA,
                STUB,
        })
        public @interface Flags {}

        private static final int CORE_APP = 1;
        private static final int SYSTEM = 1 << 1;
        private static final int FACTORY_TEST = 1 << 2;
        private static final int SYSTEM_EXT = 1 << 3;
        private static final int PRIVILEGED = 1 << 4;
        private static final int OEM = 1 << 5;
        private static final int VENDOR = 1 << 6;
        private static final int PRODUCT = 1 << 7;
        private static final int ODM = 1 << 8;
        private static final int SIGNED_WITH_PLATFORM_KEY = 1 << 9;
        private static final int NATIVE_LIBRARY_ROOT_REQUIRES_ISA = 1 << 10;
        private static final int STUB = 1 << 11;
    }

    private ParsedPackage setBoolean(@Booleans.Flags int flag, boolean value) {
        if (value) {
            mBooleans |= flag;
        } else {
            mBooleans &= ~flag;
        }
        return this;
    }

    private boolean getBoolean(@Booleans.Flags int flag) {
        return (mBooleans & flag) != 0;
    }

    // Derived fields
    private int mBaseAppInfoFlags;
    private int mBaseAppInfoPrivateFlags;
    private int mBaseAppInfoPrivateFlagsExt;
    private String mBaseAppDataCredentialProtectedDirForSystemUser;
    private String mBaseAppDataDeviceProtectedDirForSystemUser;

    @VisibleForTesting
    public PackageImpl(@NonNull String packageName, @NonNull String baseApkPath,
            @NonNull String path, @Nullable TypedArray manifestArray, boolean isCoreApp) {
        super(packageName, baseApkPath, path, manifestArray);
        this.manifestPackageName = this.packageName;
        setBoolean(Booleans.CORE_APP, isCoreApp);
    }

    @Override
    public ParsedPackage hideAsParsed() {
        super.hideAsParsed();
        return this;
    }

    @Override
    public AndroidPackage hideAsFinal() {
        // TODO(b/135203078): Lock as immutable
        assignDerivedFields();
        return this;
    }

    private void assignDerivedFields() {
        mBaseAppInfoFlags = PackageInfoUtils.appInfoFlags(this, null);
        mBaseAppInfoPrivateFlags = PackageInfoUtils.appInfoPrivateFlags(this, null);
        mBaseAppInfoPrivateFlagsExt = PackageInfoUtils.appInfoPrivateFlagsExt(this, null);
        String baseAppDataDir = Environment.getDataDirectoryPath(getVolumeUuid()) + File.separator;
        String systemUserSuffix = File.separator + UserHandle.USER_SYSTEM + File.separator;
        mBaseAppDataCredentialProtectedDirForSystemUser = TextUtils.safeIntern(
                baseAppDataDir + Environment.DIR_USER_CE + systemUserSuffix);
        mBaseAppDataDeviceProtectedDirForSystemUser = TextUtils.safeIntern(
                baseAppDataDir + Environment.DIR_USER_DE + systemUserSuffix);
    }

    @Override
    public long getLongVersionCode() {
        return PackageInfo.composeLongVersionCode(versionCodeMajor, versionCode);
    }

    @Override
    public PackageImpl removePermission(int index) {
        this.permissions.remove(index);
        return this;
    }

    @Override
    public PackageImpl addUsesOptionalLibrary(int index, String libraryName) {
        this.usesOptionalLibraries = CollectionUtils.add(usesOptionalLibraries, index,
                TextUtils.safeIntern(libraryName));
        return this;
    }

    @Override
    public PackageImpl addUsesLibrary(int index, String libraryName) {
        this.usesLibraries = CollectionUtils.add(usesLibraries, index,
                TextUtils.safeIntern(libraryName));
        return this;
    }

    @Override
    public PackageImpl removeUsesLibrary(String libraryName) {
        this.usesLibraries = CollectionUtils.remove(this.usesLibraries, libraryName);
        return this;
    }

    @Override
    public PackageImpl removeUsesOptionalLibrary(String libraryName) {
        super.removeUsesOptionalLibrary(libraryName);
        return this;
    }

    @Override
    public PackageImpl setSigningDetails(@Nullable PackageParser.SigningDetails value) {
        super.setSigningDetails(value);
        return this;
    }

    @Override
    public PackageImpl setRestrictUpdateHash(@Nullable byte... value) {
        super.setRestrictUpdateHash(value);
        return this;
    }

    @Override
    public PackageImpl setRealPackage(@Nullable String realPackage) {
        super.setRealPackage(realPackage);
        return this;
    }

    @Override
    public PackageImpl setPersistent(boolean value) {
        super.setPersistent(value);
        return this;
    }

    @Override
    public PackageImpl setDefaultToDeviceProtectedStorage(boolean value) {
        super.setDefaultToDeviceProtectedStorage(value);
        return this;
    }

    @Override
    public PackageImpl setDirectBootAware(boolean value) {
        super.setDirectBootAware(value);
        return this;
    }

    @Override
    public PackageImpl clearProtectedBroadcasts() {
        protectedBroadcasts.clear();
        return this;
    }

    @Override
    public PackageImpl clearOriginalPackages() {
        originalPackages.clear();
        return this;
    }

    @Override
    public PackageImpl clearAdoptPermissions() {
        adoptPermissions.clear();
        return this;
    }

    @Override
    public PackageImpl setCodePath(@NonNull String value) {
        this.mPath = value;
        return this;
    }

    // TODO(b/135203078): Move PackageManagerService#renameStaticSharedLibraryPackage
    //  into initial package parsing
    @Override
    public PackageImpl setPackageName(@NonNull String packageName) {
        this.packageName = TextUtils.safeIntern(packageName);

        int permissionsSize = permissions.size();
        for (int index = 0; index < permissionsSize; index++) {
            permissions.get(index).setPackageName(this.packageName);
        }

        int permissionGroupsSize = permissionGroups.size();
        for (int index = 0; index < permissionGroupsSize; index++) {
            permissionGroups.get(index).setPackageName(this.packageName);
        }

        int activitiesSize = activities.size();
        for (int index = 0; index < activitiesSize; index++) {
            activities.get(index).setPackageName(this.packageName);
        }

        int receiversSize = receivers.size();
        for (int index = 0; index < receiversSize; index++) {
            receivers.get(index).setPackageName(this.packageName);
        }

        int providersSize = providers.size();
        for (int index = 0; index < providersSize; index++) {
            providers.get(index).setPackageName(this.packageName);
        }

        int servicesSize = services.size();
        for (int index = 0; index < servicesSize; index++) {
            services.get(index).setPackageName(this.packageName);
        }

        int instrumentationsSize = instrumentations.size();
        for (int index = 0; index < instrumentationsSize; index++) {
            instrumentations.get(index).setPackageName(this.packageName);
        }

        return this;
    }

    @Override
    public PackageImpl setAllComponentsDirectBootAware(boolean allComponentsDirectBootAware) {
        int activitiesSize = activities.size();
        for (int index = 0; index < activitiesSize; index++) {
            activities.get(index).setDirectBootAware(allComponentsDirectBootAware);
        }

        int receiversSize = receivers.size();
        for (int index = 0; index < receiversSize; index++) {
            receivers.get(index).setDirectBootAware(allComponentsDirectBootAware);
        }

        int providersSize = providers.size();
        for (int index = 0; index < providersSize; index++) {
            providers.get(index).setDirectBootAware(allComponentsDirectBootAware);
        }

        int servicesSize = services.size();
        for (int index = 0; index < servicesSize; index++) {
            services.get(index).setDirectBootAware(allComponentsDirectBootAware);
        }

        return this;
    }

    @Override
    public PackageImpl setBaseCodePath(@NonNull String baseCodePath) {
        this.mBaseApkPath = TextUtils.safeIntern(baseCodePath);
        return this;
    }

    @Override
    public PackageImpl setNativeLibraryDir(@Nullable String nativeLibraryDir) {
        this.nativeLibraryDir = TextUtils.safeIntern(nativeLibraryDir);
        return this;
    }

    @Override
    public PackageImpl setNativeLibraryRootDir(@Nullable String nativeLibraryRootDir) {
        this.nativeLibraryRootDir = TextUtils.safeIntern(nativeLibraryRootDir);
        return this;
    }

    @Override
    public PackageImpl setPrimaryCpuAbi(@Nullable String primaryCpuAbi) {
        this.primaryCpuAbi = TextUtils.safeIntern(primaryCpuAbi);
        return this;
    }

    @Override
    public PackageImpl setSecondaryCpuAbi(@Nullable String secondaryCpuAbi) {
        this.secondaryCpuAbi = TextUtils.safeIntern(secondaryCpuAbi);
        return this;
    }

    @Override
    public PackageImpl setSecondaryNativeLibraryDir(@Nullable String secondaryNativeLibraryDir) {
        this.secondaryNativeLibraryDir = TextUtils.safeIntern(secondaryNativeLibraryDir);
        return this;
    }

    @Override
    public PackageImpl setSeInfo(@Nullable String seInfo) {
        this.seInfo = TextUtils.safeIntern(seInfo);
        return this;
    }

    @Override
    public PackageImpl setSeInfoUser(@Nullable String seInfoUser) {
        this.seInfoUser = TextUtils.safeIntern(seInfoUser);
        return this;
    }

    @Override
    public PackageImpl setSplitCodePaths(@Nullable String[] splitCodePaths) {
        this.splitCodePaths = splitCodePaths;
        if (splitCodePaths != null) {
            int size = splitCodePaths.length;
            for (int index = 0; index < size; index++) {
                this.splitCodePaths[index] = TextUtils.safeIntern(this.splitCodePaths[index]);
            }
        }
        return this;
    }

    @Override
    public PackageImpl capPermissionPriorities() {
        int size = permissionGroups.size();
        for (int index = size - 1; index >= 0; --index) {
            // TODO(b/135203078): Builder/immutability
            permissionGroups.get(index).setPriority(0);
        }
        return this;
    }

    @Override
    public PackageImpl markNotActivitiesAsNotExportedIfSingleUser() {
        // ignore export request for single user receivers
        int receiversSize = receivers.size();
        for (int index = 0; index < receiversSize; index++) {
            ParsedActivity receiver = receivers.get(index);
            if ((receiver.getFlags() & ActivityInfo.FLAG_SINGLE_USER) != 0) {
                receiver.setExported(false);
            }
        }

        // ignore export request for single user services
        int servicesSize = services.size();
        for (int index = 0; index < servicesSize; index++) {
            ParsedService service = services.get(index);
            if ((service.getFlags() & ActivityInfo.FLAG_SINGLE_USER) != 0) {
                service.setExported(false);
            }
        }

        // ignore export request for single user providers
        int providersSize = providers.size();
        for (int index = 0; index < providersSize; index++) {
            ParsedProvider provider = providers.get(index);
            if ((provider.getFlags() & ActivityInfo.FLAG_SINGLE_USER) != 0) {
                provider.setExported(false);
            }
        }

        return this;
    }

    @Override
    public UUID getStorageUuid() {
        return StorageManager.convert(volumeUuid);
    }

    @Deprecated
    @Override
    public String toAppInfoToString() {
        return "PackageImpl{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + getPackageName() + "}";
    }

    @Override
    public ParsedPackage setCoreApp(boolean coreApp) {
        return setBoolean(Booleans.CORE_APP, coreApp);
    }

    @Override
    public ParsedPackage setVersionCode(int versionCode) {
        this.versionCode = versionCode;
        return this;
    }

    @Override
    public ParsedPackage setVersionCodeMajor(int versionCodeMajor) {
        this.versionCodeMajor = versionCodeMajor;
        return this;
    }

    @Override
    public ApplicationInfo toAppInfoWithoutState() {
        ApplicationInfo appInfo = super.toAppInfoWithoutStateWithoutFlags();
        appInfo.flags = mBaseAppInfoFlags;
        appInfo.privateFlags = mBaseAppInfoPrivateFlags;
        appInfo.privateFlagsExt = mBaseAppInfoPrivateFlagsExt;
        appInfo.nativeLibraryDir = nativeLibraryDir;
        appInfo.nativeLibraryRootDir = nativeLibraryRootDir;
        appInfo.nativeLibraryRootRequiresIsa = nativeLibraryRootRequiresIsa;
        appInfo.primaryCpuAbi = primaryCpuAbi;
        appInfo.secondaryCpuAbi = secondaryCpuAbi;
        appInfo.secondaryNativeLibraryDir = secondaryNativeLibraryDir;
        appInfo.seInfo = seInfo;
        appInfo.seInfoUser = seInfoUser;
        appInfo.uid = uid;
        return appInfo;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        sForInternedString.parcel(this.manifestPackageName, dest, flags);
        dest.writeString(this.nativeLibraryDir);
        dest.writeString(this.nativeLibraryRootDir);
        dest.writeBoolean(this.nativeLibraryRootRequiresIsa);
        sForInternedString.parcel(this.primaryCpuAbi, dest, flags);
        sForInternedString.parcel(this.secondaryCpuAbi, dest, flags);
        dest.writeString(this.secondaryNativeLibraryDir);
        dest.writeString(this.seInfo);
        dest.writeString(this.seInfoUser);
        dest.writeInt(this.uid);
        dest.writeInt(this.mBooleans);
    }

    public PackageImpl(Parcel in) {
        super(in);
        this.manifestPackageName = sForInternedString.unparcel(in);
        this.nativeLibraryDir = in.readString();
        this.nativeLibraryRootDir = in.readString();
        this.nativeLibraryRootRequiresIsa = in.readBoolean();
        this.primaryCpuAbi = sForInternedString.unparcel(in);
        this.secondaryCpuAbi = sForInternedString.unparcel(in);
        this.secondaryNativeLibraryDir = in.readString();
        this.seInfo = in.readString();
        this.seInfoUser = in.readString();
        this.uid = in.readInt();
        this.mBooleans = in.readInt();

        assignDerivedFields();
    }

    public static final Creator<PackageImpl> CREATOR = new Creator<PackageImpl>() {
        @Override
        public PackageImpl createFromParcel(Parcel source) {
            return new PackageImpl(source);
        }

        @Override
        public PackageImpl[] newArray(int size) {
            return new PackageImpl[size];
        }
    };

    @NonNull
    @Override
    public String getManifestPackageName() {
        return manifestPackageName;
    }

    public boolean isStub() {
        return getBoolean(Booleans.STUB);
    }

    @Nullable
    @Override
    public String getNativeLibraryDir() {
        return nativeLibraryDir;
    }

    @Nullable
    @Override
    public String getNativeLibraryRootDir() {
        return nativeLibraryRootDir;
    }

    @Override
    public boolean isNativeLibraryRootRequiresIsa() {
        return nativeLibraryRootRequiresIsa;
    }

    @Nullable
    @Override
    public String getPrimaryCpuAbi() {
        return primaryCpuAbi;
    }

    @Nullable
    @Override
    public String getSecondaryCpuAbi() {
        return secondaryCpuAbi;
    }

    @Nullable
    @Override
    public String getSecondaryNativeLibraryDir() {
        return secondaryNativeLibraryDir;
    }

    @Nullable
    @Override
    public String getSeInfo() {
        return seInfo;
    }

    @Nullable
    @Override
    public String getSeInfoUser() {
        return seInfoUser;
    }

    @Override
    public boolean isCoreApp() {
        return getBoolean(Booleans.CORE_APP);
    }

    @Override
    public boolean isSystem() {
        return getBoolean(Booleans.SYSTEM);
    }

    @Override
    public boolean isFactoryTest() {
        return getBoolean(Booleans.FACTORY_TEST);
    }

    @Override
    public boolean isSystemExt() {
        return getBoolean(Booleans.SYSTEM_EXT);
    }

    @Override
    public boolean isPrivileged() {
        return getBoolean(Booleans.PRIVILEGED);
    }

    @Override
    public boolean isOem() {
        return getBoolean(Booleans.OEM);
    }

    @Override
    public boolean isVendor() {
        return getBoolean(Booleans.VENDOR);
    }

    @Override
    public boolean isProduct() {
        return getBoolean(Booleans.PRODUCT);
    }

    @Override
    public boolean isOdm() {
        return getBoolean(Booleans.ODM);
    }

    @Override
    public boolean isSignedWithPlatformKey() {
        return getBoolean(Booleans.SIGNED_WITH_PLATFORM_KEY);
    }

    /**
     * This is an appId, the uid if the userId is == USER_SYSTEM
     */
    @Override
    public int getUid() {
        return uid;
    }

    @DataClass.Generated.Member
    public PackageImpl setStub(boolean value) {
        setBoolean(Booleans.STUB, value);
        return this;
    }

    @Override
    public PackageImpl setNativeLibraryRootRequiresIsa(boolean value) {
        nativeLibraryRootRequiresIsa = value;
        return this;
    }

    @Override
    public PackageImpl setSystem(boolean value) {
        setBoolean(Booleans.SYSTEM, value);
        return this;
    }

    @Override
    public PackageImpl setFactoryTest(boolean value) {
        setBoolean(Booleans.FACTORY_TEST, value);
        return this;
    }

    @Override
    public PackageImpl setSystemExt(boolean value) {
        setBoolean(Booleans.SYSTEM_EXT, value);
        return this;
    }

    @Override
    public PackageImpl setPrivileged(boolean value) {
        setBoolean(Booleans.PRIVILEGED, value);
        return this;
    }

    @Override
    public PackageImpl setOem(boolean value) {
        setBoolean(Booleans.OEM, value);
        return this;
    }

    @Override
    public PackageImpl setVendor(boolean value) {
        setBoolean(Booleans.VENDOR, value);
        return this;
    }

    @Override
    public PackageImpl setProduct(boolean value) {
        setBoolean(Booleans.PRODUCT, value);
        return this;
    }

    @Override
    public PackageImpl setOdm(boolean value) {
        setBoolean(Booleans.ODM, value);
        return this;
    }

    @Override
    public PackageImpl setSignedWithPlatformKey(boolean value) {
        setBoolean(Booleans.SIGNED_WITH_PLATFORM_KEY, value);
        return this;
    }

    @Override
    public PackageImpl setUid(int value) {
        uid = value;
        return this;
    }

    // The following methods are explicitly not inside any interface. These are hidden under
    // PackageImpl which is only accessible to the system server. This is to prevent/discourage
    // usage of these fields outside of the utility classes.
    public String getBaseAppDataCredentialProtectedDirForSystemUser() {
        return mBaseAppDataCredentialProtectedDirForSystemUser;
    }

    public String getBaseAppDataDeviceProtectedDirForSystemUser() {
        return mBaseAppDataDeviceProtectedDirForSystemUser;
    }
}
