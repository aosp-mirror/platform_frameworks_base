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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.VersionedPackage;
import android.content.pm.dex.DexMetadataHelper;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.os.incremental.IncrementalManager;

import com.android.internal.content.NativeLibraryHelper;
import com.android.internal.pm.parsing.PackageParserException;
import com.android.internal.pm.parsing.pkg.AndroidPackageHidden;
import com.android.internal.pm.parsing.pkg.PackageImpl;
import com.android.internal.pm.pkg.component.ParsedActivity;
import com.android.internal.pm.pkg.component.ParsedInstrumentation;
import com.android.internal.pm.pkg.component.ParsedProvider;
import com.android.internal.pm.pkg.component.ParsedService;
import com.android.internal.pm.pkg.parsing.ParsingPackageHidden;
import com.android.internal.util.ArrayUtils;
import com.android.server.SystemConfig;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageState;
import com.android.server.pm.pkg.PackageStateInternal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** @hide */
public class AndroidPackageUtils {

    private AndroidPackageUtils() {
    }

    public static List<String> getAllCodePathsExcludingResourceOnly(
            AndroidPackage aPkg) {
        PackageImpl pkg = (PackageImpl) aPkg;
        ArrayList<String> paths = new ArrayList<>();
        if (pkg.isDeclaredHavingCode()) {
            paths.add(pkg.getBaseApkPath());
        }
        String[] splitCodePaths = pkg.getSplitCodePaths();
        if (!ArrayUtils.isEmpty(splitCodePaths)) {
            for (int i = 0; i < splitCodePaths.length; i++) {
                if ((pkg.getSplitFlags()[i] & ApplicationInfo.FLAG_HAS_CODE) != 0) {
                    paths.add(splitCodePaths[i]);
                }
            }
        }
        return paths;
    }

    /**
     * @return a list of the base and split code paths.
     */
    public static List<String> getAllCodePaths(AndroidPackage aPkg) {
        PackageImpl pkg = (PackageImpl) aPkg;
        ArrayList<String> paths = new ArrayList<>();
        paths.add(pkg.getBaseApkPath());

        String[] splitCodePaths = pkg.getSplitCodePaths();
        if (!ArrayUtils.isEmpty(splitCodePaths)) {
            Collections.addAll(paths, splitCodePaths);
        }
        return paths;
    }

    public static SharedLibraryInfo createSharedLibraryForSdk(AndroidPackage pkg) {
        return new SharedLibraryInfo(null, pkg.getPackageName(),
                AndroidPackageUtils.getAllCodePaths(pkg),
                pkg.getSdkLibraryName(),
                pkg.getSdkLibVersionMajor(),
                SharedLibraryInfo.TYPE_SDK_PACKAGE,
                new VersionedPackage(pkg.getManifestPackageName(),
                        pkg.getLongVersionCode()),
                null, null, false /* isNative */);
    }

    public static SharedLibraryInfo createSharedLibraryForStatic(AndroidPackage pkg) {
        return new SharedLibraryInfo(null, pkg.getPackageName(),
                AndroidPackageUtils.getAllCodePaths(pkg),
                pkg.getStaticSharedLibraryName(),
                pkg.getStaticSharedLibraryVersion(),
                SharedLibraryInfo.TYPE_STATIC,
                new VersionedPackage(pkg.getManifestPackageName(),
                        pkg.getLongVersionCode()),
                null, null, false /* isNative */);
    }

    public static SharedLibraryInfo createSharedLibraryForDynamic(AndroidPackage pkg, String name) {
        return new SharedLibraryInfo(null, pkg.getPackageName(),
                AndroidPackageUtils.getAllCodePaths(pkg), name,
                SharedLibraryInfo.VERSION_UNDEFINED,
                SharedLibraryInfo.TYPE_DYNAMIC, new VersionedPackage(pkg.getPackageName(),
                pkg.getLongVersionCode()),
                null, null, false /* isNative */);
    }

    /**
     * Return the dex metadata files for the given package as a map
     * [code path -> dex metadata path].
     *
     * NOTE: involves I/O checks.
     */
    public static Map<String, String> getPackageDexMetadata(AndroidPackage pkg) {
        return DexMetadataHelper.buildPackageApkToDexMetadataMap
                (AndroidPackageUtils.getAllCodePaths(pkg));
    }

    /**
     * Validate the dex metadata files installed for the given package.
     *
     * @throws PackageParserException in case of errors.
     */
    public static void validatePackageDexMetadata(AndroidPackage pkg)
            throws PackageParserException {
        Collection<String> apkToDexMetadataList = getPackageDexMetadata(pkg).values();
        String packageName = pkg.getPackageName();
        long versionCode = pkg.getLongVersionCode();
        final ParseTypeImpl input = ParseTypeImpl.forDefaultParsing();
        for (String dexMetadata : apkToDexMetadataList) {
            final ParseResult result = DexMetadataHelper.validateDexMetadataFile(
                    input.reset(), dexMetadata, packageName, versionCode);
            if (result.isError()) {
                throw new PackageParserException(
                        result.getErrorCode(), result.getErrorMessage(), result.getException());
            }
        }
    }

    public static NativeLibraryHelper.Handle createNativeLibraryHandle(AndroidPackage pkg)
            throws IOException {
        boolean pageSizeCompatDisabled = pkg.getPageSizeAppCompatFlags()
                == ApplicationInfo.PAGE_SIZE_APP_COMPAT_FLAG_MANIFEST_OVERRIDE_DISABLED;
        return NativeLibraryHelper.Handle.create(
                AndroidPackageUtils.getAllCodePaths(pkg),
                pkg.isMultiArch(),
                pkg.isExtractNativeLibrariesRequested(),
                pkg.isDebuggable(),
                pageSizeCompatDisabled
        );
    }

    public static boolean canHaveOatDir(@NonNull PackageState packageState,
            @NonNull AndroidPackage pkg) {
        // The following app types CANNOT have oat directory
        // - non-updated system apps,
        // - incrementally installed apps.
        if (packageState.isSystem() && !packageState.isUpdatedSystemApp()) {
            return false;
        }
        if (IncrementalManager.isIncrementalPath(pkg.getPath())) {
            return false;
        }
        return true;
    }

    public static boolean hasComponentClassName(AndroidPackage pkg, String className) {
        List<ParsedActivity> activities = pkg.getActivities();
        int activitiesSize = activities.size();
        for (int index = 0; index < activitiesSize; index++) {
            if (Objects.equals(className, activities.get(index).getName())) {
                return true;
            }
        }

        List<ParsedActivity> receivers = pkg.getReceivers();
        int receiversSize = receivers.size();
        for (int index = 0; index < receiversSize; index++) {
            if (Objects.equals(className, receivers.get(index).getName())) {
                return true;
            }
        }

        List<ParsedProvider> providers = pkg.getProviders();
        int providersSize = providers.size();
        for (int index = 0; index < providersSize; index++) {
            if (Objects.equals(className, providers.get(index).getName())) {
                return true;
            }
        }

        List<ParsedService> services = pkg.getServices();
        int servicesSize = services.size();
        for (int index = 0; index < servicesSize; index++) {
            if (Objects.equals(className, services.get(index).getName())) {
                return true;
            }
        }

        List<ParsedInstrumentation> instrumentations = pkg.getInstrumentations();
        int instrumentationsSize = instrumentations.size();
        for (int index = 0; index < instrumentationsSize; index++) {
            if (Objects.equals(className, instrumentations.get(index).getName())) {
                return true;
            }
        }

        if (pkg.getBackupAgentName() != null) {
            if (Objects.equals(className, pkg.getBackupAgentName())) {
                return true;
            }
        }

        return false;
    }

    public static boolean isEncryptionAware(AndroidPackage pkg) {
        return pkg.isDirectBootAware() || pkg.isPartiallyDirectBootAware();
    }

    public static boolean isLibrary(AndroidPackage pkg) {
        // TODO(b/135203078): Can parsing just enforce these always match?
        return pkg.getSdkLibraryName() != null || pkg.getStaticSharedLibraryName() != null
                || !pkg.getLibraryNames().isEmpty();
    }

    public static int getHiddenApiEnforcementPolicy(@NonNull AndroidPackage pkg,
            @NonNull PackageStateInternal packageState) {
        boolean isAllowedToUseHiddenApis;
        if (pkg == null) {
            isAllowedToUseHiddenApis = false;
        } else if (pkg.isSignedWithPlatformKey()) {
            isAllowedToUseHiddenApis = true;
        } else if (packageState.isSystem()) {
            isAllowedToUseHiddenApis = pkg.isNonSdkApiRequested()
                    || SystemConfig.getInstance().getHiddenApiWhitelistedApps().contains(
                    pkg.getPackageName());
        } else {
            isAllowedToUseHiddenApis = false;
        }

        if (isAllowedToUseHiddenApis) {
            return ApplicationInfo.HIDDEN_API_ENFORCEMENT_DISABLED;
        }

        // TODO(b/135203078): Handle maybeUpdateHiddenApiEnforcementPolicy. Right now it's done
        //  entirely through ApplicationInfo and shouldn't touch this specific class, but that
        //  may not always hold true.
//        if (mHiddenApiPolicy != ApplicationInfo.HIDDEN_API_ENFORCEMENT_DEFAULT) {
//            return mHiddenApiPolicy;
//        }
        return ApplicationInfo.HIDDEN_API_ENFORCEMENT_ENABLED;
    }

    /**
     * Returns false iff the provided flags include the {@link PackageManager#MATCH_SYSTEM_ONLY}
     * flag and the provided package is not a system package. Otherwise returns {@code true}.
     */
    public static boolean isMatchForSystemOnly(@NonNull PackageState packageState, long flags) {
        if ((flags & PackageManager.MATCH_SYSTEM_ONLY) != 0) {
            return packageState.isSystem();
        }
        return true;
    }

    /**
     * Returns the primary ABI as parsed from the package. Used only during parsing and derivation.
     * Otherwise prefer {@link PackageState#getPrimaryCpuAbi()}.
     */
    public static String getRawPrimaryCpuAbi(AndroidPackage pkg) {
        return ((AndroidPackageHidden) pkg).getPrimaryCpuAbi();
    }

    /**
     * Returns the secondary ABI as parsed from the package. Used only during parsing and
     * derivation. Otherwise prefer {@link PackageState#getSecondaryCpuAbi()}.
     */
    public static String getRawSecondaryCpuAbi(@NonNull AndroidPackage pkg) {
        return ((AndroidPackageHidden) pkg).getSecondaryCpuAbi();
    }

    @Deprecated
    @NonNull
    public static ApplicationInfo generateAppInfoWithoutState(AndroidPackage pkg) {
        return ((AndroidPackageHidden) pkg).toAppInfoWithoutState();
    }

    /**
     * Replacement of unnecessary legacy getRealPackage. Only returns a value if the package was
     * actually renamed.
     */
    @Nullable
    public static String getRealPackageOrNull(@NonNull AndroidPackage pkg, boolean isSystem) {
        if (pkg.getOriginalPackages().isEmpty() || !isSystem) {
            return null;
        }

        return pkg.getManifestPackageName();
    }

    public static void fillVersionCodes(@NonNull AndroidPackage pkg, @NonNull PackageInfo info) {
        info.versionCode = ((ParsingPackageHidden) pkg).getVersionCode();
        info.versionCodeMajor = ((ParsingPackageHidden) pkg).getVersionCodeMajor();
    }
}
