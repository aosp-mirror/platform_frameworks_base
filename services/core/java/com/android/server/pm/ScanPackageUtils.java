/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.content.pm.PackageManager.INSTALL_FAILED_INVALID_APK;
import static android.content.pm.PackageManager.INSTALL_FAILED_PROCESS_NOT_DEFINED;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_NO_CERTIFICATES;
import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;

import static com.android.server.pm.PackageManagerService.DEBUG_ABI_SELECTION;
import static com.android.server.pm.PackageManagerService.DEBUG_PACKAGE_SCANNING;
import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;
import static com.android.server.pm.PackageManagerService.SCAN_AS_APEX;
import static com.android.server.pm.PackageManagerService.SCAN_AS_FULL_APP;
import static com.android.server.pm.PackageManagerService.SCAN_AS_INSTANT_APP;
import static com.android.server.pm.PackageManagerService.SCAN_AS_ODM;
import static com.android.server.pm.PackageManagerService.SCAN_AS_OEM;
import static com.android.server.pm.PackageManagerService.SCAN_AS_PRIVILEGED;
import static com.android.server.pm.PackageManagerService.SCAN_AS_PRODUCT;
import static com.android.server.pm.PackageManagerService.SCAN_AS_STOPPED_SYSTEM_APP;
import static com.android.server.pm.PackageManagerService.SCAN_AS_SYSTEM;
import static com.android.server.pm.PackageManagerService.SCAN_AS_SYSTEM_EXT;
import static com.android.server.pm.PackageManagerService.SCAN_AS_VENDOR;
import static com.android.server.pm.PackageManagerService.SCAN_AS_VIRTUAL_PRELOAD;
import static com.android.server.pm.PackageManagerService.SCAN_BOOTING;
import static com.android.server.pm.PackageManagerService.SCAN_FIRST_BOOT_OR_UPGRADE;
import static com.android.server.pm.PackageManagerService.SCAN_MOVE;
import static com.android.server.pm.PackageManagerService.SCAN_NEW_INSTALL;
import static com.android.server.pm.PackageManagerService.SCAN_NO_DEX;
import static com.android.server.pm.PackageManagerService.SCAN_UPDATE_TIME;
import static com.android.server.pm.PackageManagerService.TAG;
import static com.android.server.pm.PackageManagerServiceUtils.compareSignatures;
import static com.android.server.pm.PackageManagerServiceUtils.compressedFileExists;
import static com.android.server.pm.PackageManagerServiceUtils.deriveAbiOverride;
import static com.android.server.pm.PackageManagerServiceUtils.getLastModifiedTime;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.SigningDetails;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.apk.ApkSignatureVerifier;
import android.util.jar.StrictJarFile;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.pm.parsing.pkg.ParsedPackage;
import com.android.internal.pm.pkg.component.ComponentMutateUtils;
import com.android.internal.pm.pkg.component.ParsedActivity;
import com.android.internal.pm.pkg.component.ParsedMainComponent;
import com.android.internal.pm.pkg.component.ParsedProcess;
import com.android.internal.pm.pkg.component.ParsedProvider;
import com.android.internal.pm.pkg.component.ParsedService;
import com.android.internal.pm.pkg.parsing.ParsingPackageUtils;
import com.android.internal.util.ArrayUtils;
import com.android.server.SystemConfig;
import com.android.server.pm.parsing.PackageInfoUtils;
import com.android.server.pm.parsing.library.PackageBackwardCompatibility;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateUtils;
import com.android.server.utils.WatchedArraySet;

import dalvik.system.VMRuntime;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Helper class that handles package scanning logic
 */
final class ScanPackageUtils {
    /**
     * Just scans the package without any side effects.
     *
     * @param injector injector for acquiring dependencies
     * @param request Information about the package to be scanned
     * @param isUnderFactoryTest Whether or not the device is under factory test
     * @param currentTime The current time, in millis
     * @return The results of the scan
     */
    @GuardedBy("mPm.mInstallLock")
    @VisibleForTesting
    @NonNull
    public static ScanResult scanPackageOnlyLI(@NonNull ScanRequest request,
            PackageManagerServiceInjector injector,
            boolean isUnderFactoryTest, long currentTime)
            throws PackageManagerException {
        final PackageAbiHelper packageAbiHelper = injector.getAbiHelper();
        ParsedPackage parsedPackage = request.mParsedPackage;
        PackageSetting pkgSetting = request.mPkgSetting;
        final PackageSetting disabledPkgSetting = request.mDisabledPkgSetting;
        final PackageSetting originalPkgSetting = request.mOriginalPkgSetting;
        final @ParsingPackageUtils.ParseFlags int parseFlags = request.mParseFlags;
        final @PackageManagerService.ScanFlags int scanFlags = request.mScanFlags;
        final String realPkgName = request.mRealPkgName;
        final SharedUserSetting oldSharedUserSetting = request.mOldSharedUserSetting;
        final SharedUserSetting sharedUserSetting = request.mSharedUserSetting;
        final UserHandle user = request.mUser;
        final boolean isPlatformPackage = request.mIsPlatformPackage;

        List<String> changedAbiCodePath = null;

        if (DEBUG_PACKAGE_SCANNING) {
            if ((parseFlags & ParsingPackageUtils.PARSE_CHATTY) != 0) {
                Log.d(TAG, "Scanning package " + parsedPackage.getPackageName());
            }
        }

        // Initialize package source and resource directories
        final File destCodeFile = new File(parsedPackage.getPath());

        // We keep references to the derived CPU Abis from settings in oder to reuse
        // them in the case where we're not upgrading or booting for the first time.
        String primaryCpuAbiFromSettings = null;
        String secondaryCpuAbiFromSettings = null;
        boolean needToDeriveAbi = (scanFlags & SCAN_FIRST_BOOT_OR_UPGRADE) != 0;
        boolean isApex = (scanFlags & SCAN_AS_APEX) != 0;

        if (!needToDeriveAbi) {
            if (pkgSetting != null) {
                // TODO(b/154610922): if it is not first boot or upgrade, we should directly use
                // API info from existing package setting. However, stub packages currently do not
                // preserve ABI info, thus the special condition check here. Remove the special
                // check after we fix the stub generation.
                if (pkgSetting.getPkg() != null && pkgSetting.getPkg().isStub()) {
                    needToDeriveAbi = true;
                } else {
                    primaryCpuAbiFromSettings = pkgSetting.getPrimaryCpuAbiLegacy();
                    secondaryCpuAbiFromSettings = pkgSetting.getSecondaryCpuAbiLegacy();
                }
            } else {
                // Re-scanning a system package after uninstalling updates; need to derive ABI
                needToDeriveAbi = true;
            }
        }

        if (pkgSetting != null && oldSharedUserSetting != sharedUserSetting) {
            PackageManagerService.reportSettingsProblem(Log.WARN,
                    "Package " + parsedPackage.getPackageName() + " shared user changed from "
                            + (oldSharedUserSetting != null
                            ? oldSharedUserSetting.name : "<nothing>")
                            + " to "
                            + (sharedUserSetting != null ? sharedUserSetting.name : "<nothing>")
                            + "; replacing with new");
            pkgSetting = null;
        }

        String[] usesSdkLibraries = null;
        if (!parsedPackage.getUsesSdkLibraries().isEmpty()) {
            usesSdkLibraries = new String[parsedPackage.getUsesSdkLibraries().size()];
            parsedPackage.getUsesSdkLibraries().toArray(usesSdkLibraries);
        }

        String[] usesStaticLibraries = null;
        if (!parsedPackage.getUsesStaticLibraries().isEmpty()) {
            usesStaticLibraries = new String[parsedPackage.getUsesStaticLibraries().size()];
            parsedPackage.getUsesStaticLibraries().toArray(usesStaticLibraries);
        }

        final UUID newDomainSetId = injector.getDomainVerificationManagerInternal().generateNewId();

        // TODO(b/135203078): Remove appInfoFlag usage in favor of individually assigned booleans
        //  to avoid adding something that's unsupported due to lack of state, since it's called
        //  with null.
        final boolean createNewPackage = (pkgSetting == null);
        if (createNewPackage) {
            final boolean instantApp = (scanFlags & SCAN_AS_INSTANT_APP) != 0;
            final boolean virtualPreload = (scanFlags & SCAN_AS_VIRTUAL_PRELOAD) != 0;
            final boolean isStoppedSystemApp = (scanFlags & SCAN_AS_STOPPED_SYSTEM_APP) != 0;

            // Flags contain system values stored in the server variant of AndroidPackage,
            // and so the server-side PackageInfoUtils is still called, even without a
            // PackageSetting to pass in.
            int pkgFlags = PackageInfoUtils.appInfoFlags(parsedPackage, null);
            int pkgPrivateFlags = PackageInfoUtils.appInfoPrivateFlags(parsedPackage, null);

            // REMOVE SharedUserSetting from method; update in a separate call
            pkgSetting = Settings.createNewSetting(parsedPackage.getPackageName(),
                    originalPkgSetting, disabledPkgSetting, realPkgName, sharedUserSetting,
                    destCodeFile, parsedPackage.getNativeLibraryRootDir(),
                    AndroidPackageUtils.getRawPrimaryCpuAbi(parsedPackage),
                    AndroidPackageUtils.getRawSecondaryCpuAbi(parsedPackage),
                    parsedPackage.getLongVersionCode(), pkgFlags, pkgPrivateFlags, user,
                    true /*allowInstall*/, instantApp, virtualPreload, isStoppedSystemApp,
                    UserManagerService.getInstance(), usesSdkLibraries,
                    parsedPackage.getUsesSdkLibrariesVersionsMajor(),
                    parsedPackage.getUsesSdkLibrariesOptional(), usesStaticLibraries,
                    parsedPackage.getUsesStaticLibrariesVersions(), parsedPackage.getMimeGroups(),
                    newDomainSetId,
                    parsedPackage.getTargetSdkVersion(), parsedPackage.getRestrictUpdateHash());
        } else {
            // make a deep copy to avoid modifying any existing system state.
            pkgSetting = new PackageSetting(pkgSetting);
            pkgSetting.setPkg(parsedPackage);

            // REMOVE SharedUserSetting from method; update in a separate call.
            //
            // TODO(narayan): This update is bogus. nativeLibraryDir & primaryCpuAbi,
            // secondaryCpuAbi are not known at this point so we always update them
            // to null here, only to reset them at a later point.
            Settings.updatePackageSetting(pkgSetting, disabledPkgSetting, oldSharedUserSetting,
                    sharedUserSetting, destCodeFile, parsedPackage.getNativeLibraryDir(),
                    pkgSetting.getPrimaryCpuAbi(),
                    pkgSetting.getSecondaryCpuAbi(),
                    PackageInfoUtils.appInfoFlags(parsedPackage, pkgSetting),
                    PackageInfoUtils.appInfoPrivateFlags(parsedPackage, pkgSetting),
                    UserManagerService.getInstance(),
                    usesSdkLibraries, parsedPackage.getUsesSdkLibrariesVersionsMajor(),
                    parsedPackage.getUsesSdkLibrariesOptional(),
                    usesStaticLibraries, parsedPackage.getUsesStaticLibrariesVersions(),
                    parsedPackage.getMimeGroups(), newDomainSetId,
                    parsedPackage.getTargetSdkVersion(), parsedPackage.getRestrictUpdateHash());
        }

        if (createNewPackage && originalPkgSetting != null) {
            // This is the initial transition from the original package, so,
            // fix up the new package's name now. We must do this after looking
            // up the package under its new name, so getPackageLP takes care of
            // fiddling things correctly.
            parsedPackage.setPackageName(originalPkgSetting.getPackageName());

            // File a report about this.
            String msg = "New package " + pkgSetting.getRealName()
                    + " renamed to replace old package " + pkgSetting.getPackageName();
            PackageManagerService.reportSettingsProblem(Log.WARN, msg);
        }

        final int userId = (user == null ? UserHandle.USER_SYSTEM : user.getIdentifier());
        // for existing packages, change the install state; but, only if it's explicitly specified
        if (!createNewPackage) {
            final boolean instantApp = (scanFlags & SCAN_AS_INSTANT_APP) != 0;
            final boolean fullApp = (scanFlags & SCAN_AS_FULL_APP) != 0;
            setInstantAppForUser(injector, pkgSetting, userId, instantApp, fullApp);
        }
        // TODO(patb): see if we can do away with disabled check here.
        if (disabledPkgSetting != null
                || (0 != (scanFlags & SCAN_NEW_INSTALL)
                && pkgSetting != null && pkgSetting.isSystem())) {
            pkgSetting.getPkgState().setUpdatedSystemApp(true);
        }

        pkgSetting.getTransientState().setSeInfo(SELinuxMMAC.getSeInfo(pkgSetting, parsedPackage,
                sharedUserSetting, injector.getCompatibility()));

        if (pkgSetting.isSystem()) {
            configurePackageComponents(parsedPackage);
        }

        final String cpuAbiOverride = deriveAbiOverride(request.mCpuAbiOverride);
        final boolean isSystemApp = pkgSetting.isSystem();
        final boolean isUpdatedSystemApp = pkgSetting.isUpdatedSystemApp();

        final File appLib32InstallDir = getAppLib32InstallDir();
        // The native libs of Apex is located in apex_payload.img, don't need to parse it from
        // the original apex file
        if (!isApex) {
            if ((scanFlags & SCAN_NEW_INSTALL) == 0) {
                if (needToDeriveAbi) {
                    Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "derivePackageAbi");
                    try {
                        final Pair<PackageAbiHelper.Abis, PackageAbiHelper.NativeLibraryPaths>
                                derivedAbi =
                                packageAbiHelper.derivePackageAbi(
                                        parsedPackage,
                                        isSystemApp,
                                        isUpdatedSystemApp,
                                        cpuAbiOverride,
                                        appLib32InstallDir);
                        derivedAbi.first.applyTo(parsedPackage);
                        derivedAbi.second.applyTo(parsedPackage);
                    } finally {
                        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                    }

                    // Some system apps still use directory structure for native libraries
                    // in which case we might end up not detecting abi solely based on apk
                    // structure. Try to detect abi based on directory structure.

                    String pkgRawPrimaryCpuAbi = AndroidPackageUtils.getRawPrimaryCpuAbi(
                            parsedPackage);
                    if (isSystemApp && !isUpdatedSystemApp && pkgRawPrimaryCpuAbi == null) {
                        final PackageAbiHelper.Abis abis = packageAbiHelper.getBundledAppAbis(
                                parsedPackage);
                        abis.applyTo(parsedPackage);
                        abis.applyTo(pkgSetting);
                        final PackageAbiHelper.NativeLibraryPaths nativeLibraryPaths =
                                packageAbiHelper.deriveNativeLibraryPaths(parsedPackage,
                                        isSystemApp, isUpdatedSystemApp, appLib32InstallDir);
                        nativeLibraryPaths.applyTo(parsedPackage);
                    }
                } else {
                    // This is not a first boot or an upgrade, don't bother deriving the
                    // ABI during the scan. Instead, trust the value that was stored in the
                    // package setting.
                    parsedPackage.setPrimaryCpuAbi(primaryCpuAbiFromSettings)
                            .setSecondaryCpuAbi(secondaryCpuAbiFromSettings);

                    final PackageAbiHelper.NativeLibraryPaths nativeLibraryPaths =
                            packageAbiHelper.deriveNativeLibraryPaths(parsedPackage, isSystemApp,
                                    isUpdatedSystemApp, appLib32InstallDir);
                    nativeLibraryPaths.applyTo(parsedPackage);

                    if (DEBUG_ABI_SELECTION) {
                        Slog.i(TAG, "Using ABIS and native lib paths from settings : "
                                + parsedPackage.getPackageName() + " "
                                + AndroidPackageUtils.getRawPrimaryCpuAbi(parsedPackage)
                                + ", "
                                + AndroidPackageUtils.getRawSecondaryCpuAbi(parsedPackage));
                    }
                }
            } else {
                if ((scanFlags & SCAN_MOVE) != 0) {
                    // We haven't run dex-opt for this move (since we've moved the compiled output
                    // too) but we already have this packages package info in the PackageSetting.
                    // We just use that and derive the native library path based on the new code
                    // path.
                    parsedPackage.setPrimaryCpuAbi(pkgSetting.getPrimaryCpuAbiLegacy())
                            .setSecondaryCpuAbi(pkgSetting.getSecondaryCpuAbiLegacy());
                }

                // Set native library paths again. For moves, the path will be updated based on the
                // ABIs we've determined above. For non-moves, the path will be updated based on the
                // ABIs we determined during compilation, but the path will depend on the final
                // package path (after the rename away from the stage path).
                final PackageAbiHelper.NativeLibraryPaths nativeLibraryPaths =
                        packageAbiHelper.deriveNativeLibraryPaths(parsedPackage, isSystemApp,
                                isUpdatedSystemApp, appLib32InstallDir);
                nativeLibraryPaths.applyTo(parsedPackage);
            }

            // This is a special case for the "system" package, where the ABI is
            // dictated by the zygote configuration (and init.rc). We should keep track
            // of this ABI so that we can deal with "normal" applications that run under
            // the same UID correctly.
            if (isPlatformPackage) {
                parsedPackage.setPrimaryCpuAbi(VMRuntime.getRuntime().is64Bit()
                        ? Build.SUPPORTED_64_BIT_ABIS[0] : Build.SUPPORTED_32_BIT_ABIS[0]);
            }
        }

        // If there's a mismatch between the abi-override in the package setting
        // and the abiOverride specified for the install. Warn about this because we
        // would've already compiled the app without taking the package setting into
        // account.
        if ((scanFlags & SCAN_NO_DEX) == 0 && (scanFlags & SCAN_NEW_INSTALL) != 0) {
            if (cpuAbiOverride == null) {
                Slog.w(TAG, "Ignoring persisted ABI override for package "
                        + parsedPackage.getPackageName());
            }
        }

        pkgSetting.setPrimaryCpuAbi(AndroidPackageUtils.getRawPrimaryCpuAbi(parsedPackage))
                .setSecondaryCpuAbi(AndroidPackageUtils.getRawSecondaryCpuAbi(parsedPackage))
                .setCpuAbiOverride(cpuAbiOverride);

        if (DEBUG_ABI_SELECTION) {
            Slog.d(TAG, "Resolved nativeLibraryRoot for " + parsedPackage.getPackageName()
                    + " to root=" + parsedPackage.getNativeLibraryRootDir()
                    + ", to dir=" + parsedPackage.getNativeLibraryDir()
                    + ", isa=" + parsedPackage.isNativeLibraryRootRequiresIsa());
        }

        // Push the derived path down into PackageSettings so we know what to
        // clean up at uninstall time.
        pkgSetting.setLegacyNativeLibraryPath(parsedPackage.getNativeLibraryRootDir());

        if (DEBUG_ABI_SELECTION) {
            Log.d(TAG, "Abis for package[" + parsedPackage.getPackageName() + "] are"
                    + " primary=" + pkgSetting.getPrimaryCpuAbiLegacy()
                    + " secondary=" + pkgSetting.getSecondaryCpuAbiLegacy()
                    + " abiOverride=" + pkgSetting.getCpuAbiOverride());
        }

        if ((scanFlags & SCAN_BOOTING) == 0 && oldSharedUserSetting != null) {
            // We don't do this here during boot because we can do it all
            // at once after scanning all existing packages.
            //
            // We also do this *before* we perform dexopt on this package, so that
            // we can avoid redundant dexopts, and also to make sure we've got the
            // code and package path correct.
            changedAbiCodePath = applyAdjustedAbiToSharedUser(oldSharedUserSetting,
                    parsedPackage, packageAbiHelper.getAdjustedAbiForSharedUser(
                            oldSharedUserSetting.getPackageStates(), parsedPackage));
        }

        parsedPackage.setFactoryTest(isUnderFactoryTest && parsedPackage.getRequestedPermissions()
                .contains(android.Manifest.permission.FACTORY_TEST));

        if (isSystemApp) {
            pkgSetting.setIsOrphaned(true);
        }

        // Take care of first install / last update times.
        final long scanFileTime = getLastModifiedTime(parsedPackage);
        final long existingFirstInstallTime = userId == UserHandle.USER_ALL
                ? PackageStateUtils.getEarliestFirstInstallTime(pkgSetting.getUserStates())
                : pkgSetting.readUserState(userId).getFirstInstallTimeMillis();
        if (currentTime != 0) {
            if (existingFirstInstallTime == 0) {
                pkgSetting.setFirstInstallTime(currentTime, userId)
                        .setLastUpdateTime(currentTime);
            } else if ((scanFlags & SCAN_UPDATE_TIME) != 0) {
                pkgSetting.setLastUpdateTime(currentTime);
            }
        } else if (existingFirstInstallTime == 0) {
            // We need *something*.  Take time stamp of the file.
            pkgSetting.setFirstInstallTime(scanFileTime, userId)
                    .setLastUpdateTime(scanFileTime);
        } else if ((parseFlags & ParsingPackageUtils.PARSE_IS_SYSTEM_DIR) != 0) {
            if (scanFileTime != pkgSetting.getLastModifiedTime()) {
                // A package on the system image has changed; consider this
                // to be an update.
                pkgSetting.setLastUpdateTime(scanFileTime);
            }
        }
        pkgSetting.setLastModifiedTime(scanFileTime);
        // TODO(b/135203078): Remove, move to constructor
        pkgSetting.setPkg(parsedPackage)
                .setFlags(PackageInfoUtils.appInfoFlags(parsedPackage, pkgSetting))
                .setPrivateFlags(PackageInfoUtils.appInfoPrivateFlags(parsedPackage, pkgSetting));
        if (parsedPackage.getLongVersionCode() != pkgSetting.getVersionCode()) {
            pkgSetting.setLongVersionCode(parsedPackage.getLongVersionCode());
        }
        // Update volume if needed
        final String volumeUuid = parsedPackage.getVolumeUuid();
        if (!Objects.equals(volumeUuid, pkgSetting.getVolumeUuid())) {
            Slog.i(PackageManagerService.TAG,
                    "Update" + (pkgSetting.isSystem() ? " system" : "")
                            + " package " + parsedPackage.getPackageName()
                            + " volume from " + pkgSetting.getVolumeUuid()
                            + " to " + volumeUuid);
            pkgSetting.setVolumeUuid(volumeUuid);
        }

        SharedLibraryInfo sdkLibraryInfo = null;
        if (!TextUtils.isEmpty(parsedPackage.getSdkLibraryName())) {
            sdkLibraryInfo = AndroidPackageUtils.createSharedLibraryForSdk(parsedPackage);
        }
        SharedLibraryInfo staticSharedLibraryInfo = null;
        if (!TextUtils.isEmpty(parsedPackage.getStaticSharedLibraryName())) {
            staticSharedLibraryInfo =
                    AndroidPackageUtils.createSharedLibraryForStatic(parsedPackage);
        }
        List<SharedLibraryInfo> dynamicSharedLibraryInfos = null;
        if (!ArrayUtils.isEmpty(parsedPackage.getLibraryNames())) {
            dynamicSharedLibraryInfos = new ArrayList<>(parsedPackage.getLibraryNames().size());
            for (String name : parsedPackage.getLibraryNames()) {
                dynamicSharedLibraryInfos.add(
                        AndroidPackageUtils.createSharedLibraryForDynamic(parsedPackage, name));
            }
        }

        return new ScanResult(request, pkgSetting, changedAbiCodePath,
                !createNewPackage /* existingSettingCopied */,
                Process.INVALID_UID /* previousAppId */ , sdkLibraryInfo,
                staticSharedLibraryInfo, dynamicSharedLibraryInfos);
    }

    /**
     * Returns the actual scan flags depending upon the state of the other settings.
     * <p>Updated system applications will not have the following flags set
     * by default and need to be adjusted after the fact:
     * <ul>
     * <li>{@link PackageManagerService.SCAN_AS_SYSTEM}</li>
     * <li>{@link PackageManagerService.SCAN_AS_PRIVILEGED}</li>
     * <li>{@link PackageManagerService.SCAN_AS_OEM}</li>
     * <li>{@link PackageManagerService.SCAN_AS_VENDOR}</li>
     * <li>{@link PackageManagerService.SCAN_AS_PRODUCT}</li>
     * <li>{@link PackageManagerService.SCAN_AS_SYSTEM_EXT}</li>
     * <li>{@link PackageManagerService.SCAN_AS_INSTANT_APP}</li>
     * <li>{@link PackageManagerService.SCAN_AS_VIRTUAL_PRELOAD}</li>
     * <li>{@link PackageManagerService.SCAN_AS_ODM}</li>
     * </ul>
     */
    public static @PackageManagerService.ScanFlags int adjustScanFlagsWithPackageSetting(
            @PackageManagerService.ScanFlags int scanFlags,
            PackageSetting pkgSetting, PackageSetting disabledPkgSetting, UserHandle user) {

        // TODO(patb): Do away entirely with disabledPkgSetting here. PkgSetting will always contain
        // the correct isSystem value now that we don't disable system packages before scan.
        final PackageSetting systemPkgSetting =
                (scanFlags & SCAN_NEW_INSTALL) != 0 && disabledPkgSetting == null
                        && pkgSetting != null && pkgSetting.isSystem()
                        ? pkgSetting
                        : disabledPkgSetting;
        if (systemPkgSetting != null)  {
            // updated system application, must at least have SCAN_AS_SYSTEM
            scanFlags |= SCAN_AS_SYSTEM;
            if ((systemPkgSetting.getPrivateFlags()
                    & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0) {
                scanFlags |= SCAN_AS_PRIVILEGED;
            }
            if ((systemPkgSetting.getPrivateFlags()
                    & ApplicationInfo.PRIVATE_FLAG_OEM) != 0) {
                scanFlags |= SCAN_AS_OEM;
            }
            if ((systemPkgSetting.getPrivateFlags()
                    & ApplicationInfo.PRIVATE_FLAG_VENDOR) != 0) {
                scanFlags |= SCAN_AS_VENDOR;
            }
            if ((systemPkgSetting.getPrivateFlags()
                    & ApplicationInfo.PRIVATE_FLAG_PRODUCT) != 0) {
                scanFlags |= SCAN_AS_PRODUCT;
            }
            if ((systemPkgSetting.getPrivateFlags()
                    & ApplicationInfo.PRIVATE_FLAG_SYSTEM_EXT) != 0) {
                scanFlags |= SCAN_AS_SYSTEM_EXT;
            }
            if ((systemPkgSetting.getPrivateFlags()
                    & ApplicationInfo.PRIVATE_FLAG_ODM) != 0) {
                scanFlags |= SCAN_AS_ODM;
            }
        }
        if (pkgSetting != null) {
            final int userId = ((user == null) ? 0 : user.getIdentifier());
            if (pkgSetting.getInstantApp(userId)) {
                scanFlags |= SCAN_AS_INSTANT_APP;
            }
            if (pkgSetting.getVirtualPreload(userId)) {
                scanFlags |= SCAN_AS_VIRTUAL_PRELOAD;
            }
        }

        return scanFlags;
    }

    /**
     * Enforces code policy for the package. This ensures that if an APK has
     * declared hasCode="true" in its manifest that the APK actually contains
     * code.
     *
     * @throws PackageManagerException If bytecode could not be found when it should exist
     */
    public static void assertCodePolicy(AndroidPackage pkg)
            throws PackageManagerException {
        final boolean shouldHaveCode = pkg.isDeclaredHavingCode();
        if (shouldHaveCode && !apkHasCode(pkg.getBaseApkPath())) {
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                    "Package " + pkg.getBaseApkPath() + " code is missing");
        }

        if (!ArrayUtils.isEmpty(pkg.getSplitCodePaths())) {
            for (int i = 0; i < pkg.getSplitCodePaths().length; i++) {
                final boolean splitShouldHaveCode =
                        (pkg.getSplitFlags()[i] & ApplicationInfo.FLAG_HAS_CODE) != 0;
                if (splitShouldHaveCode && !apkHasCode(pkg.getSplitCodePaths()[i])) {
                    throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                            "Package " + pkg.getSplitCodePaths()[i] + " code is missing");
                }
            }
        }
    }

    public static void assertStaticSharedLibraryIsValid(AndroidPackage pkg,
            @PackageManagerService.ScanFlags int scanFlags) throws PackageManagerException {
        // Static shared libraries should have at least O target SDK
        if (pkg.getTargetSdkVersion() < Build.VERSION_CODES.O) {
            throw PackageManagerException.ofInternalError(
                    "Packages declaring static-shared libs must target O SDK or higher",
                    PackageManagerException.INTERNAL_ERROR_STATIC_SHARED_LIB_LOW_SDK);
        }

        // Package declaring static a shared lib cannot be instant apps
        if ((scanFlags & SCAN_AS_INSTANT_APP) != 0) {
            throw PackageManagerException.ofInternalError(
                    "Packages declaring static-shared libs cannot be instant apps",
                    PackageManagerException.INTERNAL_ERROR_STATIC_SHARED_LIB_INSTANT);
        }

        // Package declaring static a shared lib cannot be renamed since the package
        // name is synthetic and apps can't code around package manager internals.
        if (!ArrayUtils.isEmpty(pkg.getOriginalPackages())) {
            throw PackageManagerException.ofInternalError(
                    "Packages declaring static-shared libs cannot be renamed",
                    PackageManagerException.INTERNAL_ERROR_STATIC_SHARED_LIB_RENAMED);
        }

        // Package declaring static a shared lib cannot declare dynamic libs
        if (!ArrayUtils.isEmpty(pkg.getLibraryNames())) {
            throw PackageManagerException.ofInternalError(
                    "Packages declaring static-shared libs cannot declare dynamic libs",
                    PackageManagerException.INTERNAL_ERROR_STATIC_SHARED_LIB_DYNAMIC);
        }

        // Package declaring static a shared lib cannot declare shared users
        if (pkg.getSharedUserId() != null) {
            throw PackageManagerException.ofInternalError(
                    "Packages declaring static-shared libs cannot declare shared users",
                    PackageManagerException.INTERNAL_ERROR_STATIC_SHARED_LIB_SHARED_USER);
        }

        // Static shared libs cannot declare activities
        if (!pkg.getActivities().isEmpty()) {
            throw PackageManagerException.ofInternalError(
                    "Static shared libs cannot declare activities",
                    PackageManagerException.INTERNAL_ERROR_STATIC_SHARED_LIB_ACTIVITY);
        }

        // Static shared libs cannot declare services
        if (!pkg.getServices().isEmpty()) {
            throw PackageManagerException.ofInternalError(
                    "Static shared libs cannot declare services",
                    PackageManagerException.INTERNAL_ERROR_STATIC_SHARED_LIB_SERVICE);
        }

        // Static shared libs cannot declare providers
        if (!pkg.getProviders().isEmpty()) {
            throw PackageManagerException.ofInternalError(
                    "Static shared libs cannot declare content providers",
                    PackageManagerException.INTERNAL_ERROR_STATIC_SHARED_LIB_CONTENT_PROVIDER);
        }

        // Static shared libs cannot declare receivers
        if (!pkg.getReceivers().isEmpty()) {
            throw PackageManagerException.ofInternalError(
                    "Static shared libs cannot declare broadcast receivers",
                    PackageManagerException.INTERNAL_ERROR_STATIC_SHARED_LIB_BROADCAST_RECEIVER);
        }

        // Static shared libs cannot declare permission groups
        if (!pkg.getPermissionGroups().isEmpty()) {
            throw PackageManagerException.ofInternalError(
                    "Static shared libs cannot declare permission groups",
                    PackageManagerException.INTERNAL_ERROR_STATIC_SHARED_LIB_PERMISSION_GROUP);
        }

        // Static shared libs cannot declare attributions
        if (!pkg.getAttributions().isEmpty()) {
            throw PackageManagerException.ofInternalError(
                    "Static shared libs cannot declare features",
                    PackageManagerException.INTERNAL_ERROR_STATIC_SHARED_LIB_FEATURE);
        }

        // Static shared libs cannot declare permissions
        if (!pkg.getPermissions().isEmpty()) {
            throw PackageManagerException.ofInternalError(
                    "Static shared libs cannot declare permissions",
                    PackageManagerException.INTERNAL_ERROR_STATIC_SHARED_LIB_PERMISSION);
        }

        // Static shared libs cannot declare protected broadcasts
        if (!pkg.getProtectedBroadcasts().isEmpty()) {
            throw PackageManagerException.ofInternalError(
                    "Static shared libs cannot declare protected broadcasts",
                    PackageManagerException.INTERNAL_ERROR_STATIC_SHARED_LIB_PROTECTED_BROADCAST);
        }

        // Static shared libs cannot be overlay targets
        if (pkg.getOverlayTarget() != null) {
            throw PackageManagerException.ofInternalError(
                    "Static shared libs cannot be overlay targets",
                    PackageManagerException.INTERNAL_ERROR_STATIC_SHARED_LIB_OVERLAY_TARGETS);
        }
    }

    public static void assertProcessesAreValid(AndroidPackage pkg) throws PackageManagerException {
        final Map<String, ParsedProcess> procs = pkg.getProcesses();
        if (!procs.isEmpty()) {
            if (!procs.containsKey(pkg.getProcessName())) {
                throw new PackageManagerException(
                        INSTALL_FAILED_PROCESS_NOT_DEFINED,
                        "Can't install because application tag's process attribute "
                                + pkg.getProcessName()
                                + " (in package " + pkg.getPackageName()
                                + ") is not included in the <processes> list");
            }
            assertPackageProcesses(pkg, pkg.getActivities(), procs, "activity");
            assertPackageProcesses(pkg, pkg.getServices(), procs, "service");
            assertPackageProcesses(pkg, pkg.getReceivers(), procs, "receiver");
            assertPackageProcesses(pkg, pkg.getProviders(), procs, "provider");
        }
    }

    private static <T extends ParsedMainComponent> void assertPackageProcesses(AndroidPackage pkg,
            List<T> components, Map<String, ParsedProcess> procs, String compName)
            throws PackageManagerException {
        if (components == null) {
            return;
        }
        for (int i = components.size() - 1; i >= 0; i--) {
            final ParsedMainComponent component = components.get(i);
            if (!procs.containsKey(component.getProcessName())) {
                throw new PackageManagerException(
                        INSTALL_FAILED_PROCESS_NOT_DEFINED,
                        "Can't install because " + compName + " " + component.getClassName()
                                + "'s process attribute " + component.getProcessName()
                                + " (in package " + pkg.getPackageName()
                                + ") is not included in the <processes> list");
            }
        }
    }

    public static void assertMinSignatureSchemeIsValid(AndroidPackage pkg,
            @ParsingPackageUtils.ParseFlags int parseFlags) throws PackageManagerException {
        int minSignatureSchemeVersion =
                ApkSignatureVerifier.getMinimumSignatureSchemeVersionForTargetSdk(
                        pkg.getTargetSdkVersion());
        if (pkg.getSigningDetails().getSignatureSchemeVersion()
                < minSignatureSchemeVersion) {
            throw new PackageManagerException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    "No signature found in package of version " + minSignatureSchemeVersion
                            + " or newer for package " + pkg.getPackageName());
        }
    }

    /**
     * Returns the "real" name of the package.
     * <p>This may differ from the package's actual name if the application has already
     * been installed under one of this package's original names.
     */
    public static @Nullable String getRealPackageName(@NonNull AndroidPackage pkg,
            @Nullable String renamedPkgName, boolean isSystemApp) {
        if (isPackageRenamed(pkg, renamedPkgName)) {
            return AndroidPackageUtils.getRealPackageOrNull(pkg, isSystemApp);
        }
        return null;
    }

    /** Returns {@code true} if the package has been renamed. Otherwise, {@code false}. */
    public static boolean isPackageRenamed(@NonNull AndroidPackage pkg,
            @Nullable String renamedPkgName) {
        return pkg.getOriginalPackages().contains(renamedPkgName);
    }

    /**
     * Renames the package if it was installed under a different name.
     * <p>When we've already installed the package under an original name, update
     * the new package so we can continue to have the old name.
     */
    public static void ensurePackageRenamed(@NonNull ParsedPackage parsedPackage,
            @NonNull String renamedPackageName) {
        if (!parsedPackage.getOriginalPackages().contains(renamedPackageName)
                || parsedPackage.getPackageName().equals(renamedPackageName)) {
            return;
        }
        parsedPackage.setPackageName(renamedPackageName);
    }

    /**
     * Returns {@code true} if the given file contains code. Otherwise {@code false}.
     */
    public static boolean apkHasCode(String fileName) {
        StrictJarFile jarFile = null;
        try {
            jarFile = new StrictJarFile(fileName,
                    false /*verify*/, false /*signatureSchemeRollbackProtectionsEnforced*/);
            return jarFile.findEntry("classes.dex") != null;
        } catch (IOException ignore) {
        } finally {
            try {
                if (jarFile != null) {
                    jarFile.close();
                }
            } catch (IOException ignore) {
            }
        }
        return false;
    }

    /**
     * Sets the enabled state of components configured through {@link SystemConfig}.
     * This modifies the {@link PackageSetting} object.
     *
     * TODO(b/135203078): Move this to package parsing
     **/
    public static void configurePackageComponents(AndroidPackage pkg) {
        final ArrayMap<String, Boolean> componentsEnabledStates = SystemConfig.getInstance()
                .getComponentsEnabledStates(pkg.getPackageName());
        if (componentsEnabledStates == null) {
            return;
        }

        for (int i = ArrayUtils.size(pkg.getActivities()) - 1; i >= 0; i--) {
            final ParsedActivity component = pkg.getActivities().get(i);
            final Boolean enabled = componentsEnabledStates.get(component.getName());
            if (enabled != null) {
                ComponentMutateUtils.setEnabled(component, enabled);
            }
        }

        for (int i = ArrayUtils.size(pkg.getReceivers()) - 1; i >= 0; i--) {
            final ParsedActivity component = pkg.getReceivers().get(i);
            final Boolean enabled = componentsEnabledStates.get(component.getName());
            if (enabled != null) {
                ComponentMutateUtils.setEnabled(component, enabled);
            }
        }

        for (int i = ArrayUtils.size(pkg.getProviders()) - 1; i >= 0; i--) {
            final ParsedProvider component = pkg.getProviders().get(i);
            final Boolean enabled = componentsEnabledStates.get(component.getName());
            if (enabled != null) {
                ComponentMutateUtils.setEnabled(component, enabled);
            }
        }

        for (int i = ArrayUtils.size(pkg.getServices()) - 1; i >= 0; i--) {
            final ParsedService component = pkg.getServices().get(i);
            final Boolean enabled = componentsEnabledStates.get(component.getName());
            if (enabled != null) {
                ComponentMutateUtils.setEnabled(component, enabled);
            }
        }
    }

    public static int getVendorPartitionVersion() {
        final String version = SystemProperties.get("ro.vndk.version");
        if (!version.isEmpty()) {
            try {
                return Integer.parseInt(version);
            } catch (NumberFormatException ignore) {
                if (ArrayUtils.contains(Build.VERSION.ACTIVE_CODENAMES, version)) {
                    return Build.VERSION_CODES.CUR_DEVELOPMENT;
                }
            }
        }
        return Build.VERSION_CODES.P;
    }

    /**
     * Applies policy to the parsed package based upon the given policy flags.
     * Ensures the package is in a good state.
     * <p>
     * Implementation detail: This method must NOT have any side effect. It would
     * ideally be static, but, it requires locks to read system state.
     */
    public static void applyPolicy(ParsedPackage parsedPackage,
            final @PackageManagerService.ScanFlags int scanFlags,
            @Nullable AndroidPackage platformPkg, boolean isUpdatedSystemApp) {
        // TODO: In the real APIs, an updated system app is always a system app, but that may not
        //  hold true during scan because PMS doesn't propagate the SCAN_AS_SYSTEM flag for the data
        //  directory. This tries to emulate that behavior by using either the flag or the boolean,
        //  but this logic is fragile. Specifically, it may affect the PackageBackwardCompatibility
        //  checker, which switches branches based on whether an app is a system app. When install
        //  is refactored, the scan policy flags should not be read this late and instead passed
        //  around in the PackageSetting or a temporary object which infers these values early, so
        //  that all further consumers agree on their values.
        boolean isSystemApp = isUpdatedSystemApp;
        if ((scanFlags & SCAN_AS_SYSTEM) != 0) {
            isSystemApp = true;
            parsedPackage.setSystem(true);
            // TODO(b/135203078): Can this be done in PackageParser? Or just inferred when the flag
            //  is set during parse.
            if (parsedPackage.isDirectBootAware()) {
                parsedPackage.setAllComponentsDirectBootAware(true);
            }
            if (compressedFileExists(parsedPackage.getPath())) {
                parsedPackage.setStub(true);
            }
        } else {
            parsedPackage
                    // Non system apps cannot mark any broadcast as protected
                    .clearProtectedBroadcasts()
                    // non system apps can't be flagged as core
                    .setCoreApp(false)
                    // clear flags not applicable to regular apps
                    .setPersistent(false)
                    .setDefaultToDeviceProtectedStorage(false)
                    .setDirectBootAware(false)
                    // non system apps can't have permission priority
                    .capPermissionPriorities();
        }
        if ((scanFlags & SCAN_AS_PRIVILEGED) == 0) {
            parsedPackage
                    .markNotActivitiesAsNotExportedIfSingleUser();
        }

        parsedPackage.setApex((scanFlags & SCAN_AS_APEX) != 0);

        parsedPackage.setPrivileged((scanFlags & SCAN_AS_PRIVILEGED) != 0)
                .setOem((scanFlags & SCAN_AS_OEM) != 0)
                .setVendor((scanFlags & SCAN_AS_VENDOR) != 0)
                .setProduct((scanFlags & SCAN_AS_PRODUCT) != 0)
                .setSystemExt((scanFlags & SCAN_AS_SYSTEM_EXT) != 0)
                .setOdm((scanFlags & SCAN_AS_ODM) != 0);

        // Check if the package is signed with the same key as the platform package.
        parsedPackage.setSignedWithPlatformKey(
                (PLATFORM_PACKAGE_NAME.equals(parsedPackage.getPackageName())
                        || (platformPkg != null && compareSignatures(
                        platformPkg.getSigningDetails(),
                        parsedPackage.getSigningDetails()
                ) == PackageManager.SIGNATURE_MATCH))
        );

        if (!isSystemApp) {
            // Only system apps can use these features.
            parsedPackage.clearOriginalPackages()
                    .clearAdoptPermissions();
        }

        PackageBackwardCompatibility.modifySharedLibraries(parsedPackage, isSystemApp,
                isUpdatedSystemApp);
    }

    /**
     * Applies the adjusted ABI calculated by
     * {@link PackageAbiHelper#getAdjustedAbiForSharedUser(ArraySet, AndroidPackage)} to all
     * relevant packages and settings.
     * @param sharedUserSetting The {@code SharedUserSetting} to adjust
     * @param scannedPackage the package being scanned or null
     * @param adjustedAbi the adjusted ABI calculated by {@link PackageAbiHelper}
     * @return the list of code paths that belong to packages that had their ABIs adjusted.
     */
    public static List<String> applyAdjustedAbiToSharedUser(SharedUserSetting sharedUserSetting,
            ParsedPackage scannedPackage, String adjustedAbi) {
        if (scannedPackage != null)  {
            scannedPackage.setPrimaryCpuAbi(adjustedAbi);
        }
        List<String> changedAbiCodePath = null;
        final WatchedArraySet<PackageSetting> sharedUserPackageSettings =
                sharedUserSetting.getPackageSettings();
        for (int i = 0; i < sharedUserPackageSettings.size(); i++) {
            PackageSetting ps = sharedUserPackageSettings.valueAt(i);
            if (scannedPackage == null
                    || !scannedPackage.getPackageName().equals(ps.getPackageName())) {
                if (ps.getPrimaryCpuAbiLegacy() != null) {
                    continue;
                }

                ps.setPrimaryCpuAbi(adjustedAbi);
                ps.onChanged();
                if (ps.getPkg() != null) {
                    if (!TextUtils.equals(adjustedAbi,
                            AndroidPackageUtils.getRawPrimaryCpuAbi(ps.getPkg()))) {
                        if (DEBUG_ABI_SELECTION) {
                            Slog.i(TAG,
                                    "Adjusting ABI for " + ps.getPackageName() + " to "
                                            + adjustedAbi + " (scannedPackage="
                                            + (scannedPackage != null ? scannedPackage : "null")
                                            + ")");
                        }
                        if (changedAbiCodePath == null) {
                            changedAbiCodePath = new ArrayList<>();
                        }
                        changedAbiCodePath.add(ps.getPathString());
                    }
                }
            }
        }
        return changedAbiCodePath;
    }

    public static void collectCertificatesLI(PackageSetting ps, ParsedPackage parsedPackage,
            Settings.VersionInfo settingsVersionForPackage, boolean forceCollect,
            boolean skipVerify, boolean isPreNMR1Upgrade)
            throws PackageManagerException {
        // When upgrading from pre-N MR1, verify the package time stamp using the package
        // directory and not the APK file.
        final long lastModifiedTime = isPreNMR1Upgrade
                ? new File(parsedPackage.getPath()).lastModified()
                : getLastModifiedTime(parsedPackage);
        if (ps != null && !forceCollect
                && ps.getPathString().equals(parsedPackage.getPath())
                && ps.getLastModifiedTime() == lastModifiedTime
                && !ReconcilePackageUtils.isCompatSignatureUpdateNeeded(settingsVersionForPackage)
                && !ReconcilePackageUtils.isRecoverSignatureUpdateNeeded(
                settingsVersionForPackage)) {
            if (ps.getSigningDetails().getSignatures() != null
                    && ps.getSigningDetails().getSignatures().length != 0
                    && ps.getSigningDetails().getSignatureSchemeVersion()
                    != SigningDetails.SignatureSchemeVersion.UNKNOWN) {
                // Optimization: reuse the existing cached signing data
                // if the package appears to be unchanged.
                parsedPackage.setSigningDetails(
                        new SigningDetails(ps.getSigningDetails()));
                return;
            }

            Slog.w(TAG, "PackageSetting for " + ps.getPackageName()
                    + " is missing signatures.  Collecting certs again to recover them.");
        } else {
            Slog.i(TAG, parsedPackage.getPath() + " changed; collecting certs"
                    + (forceCollect ? " (forced)" : ""));
        }

        try {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "collectCertificates");
            final ParseTypeImpl input = ParseTypeImpl.forDefaultParsing();
            final ParseResult<SigningDetails> result = ParsingPackageUtils.getSigningDetails(
                    input, parsedPackage, skipVerify);
            if (result.isError()) {
                throw new PackageManagerException(
                        result.getErrorCode(), result.getErrorMessage(), result.getException());
            }
            parsedPackage.setSigningDetails(result.getResult());
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    public static void setInstantAppForUser(PackageManagerServiceInjector injector,
            PackageSetting pkgSetting, int userId, boolean instantApp, boolean fullApp) {
        // no state specified; do nothing
        if (!instantApp && !fullApp) {
            return;
        }
        if (userId != UserHandle.USER_ALL) {
            if (instantApp && !pkgSetting.getInstantApp(userId)) {
                pkgSetting.setInstantApp(true /*instantApp*/, userId);
            } else if (fullApp && pkgSetting.getInstantApp(userId)) {
                pkgSetting.setInstantApp(false /*instantApp*/, userId);
            }
        } else {
            for (int currentUserId : injector.getUserManagerInternal().getUserIds()) {
                if (instantApp && !pkgSetting.getInstantApp(currentUserId)) {
                    pkgSetting.setInstantApp(true /*instantApp*/, currentUserId);
                } else if (fullApp && pkgSetting.getInstantApp(currentUserId)) {
                    pkgSetting.setInstantApp(false /*instantApp*/, currentUserId);
                }
            }
        }
    }

    /** Directory where installed application's 32-bit native libraries are copied. */
    public static File getAppLib32InstallDir() {
        return new File(Environment.getDataDirectory(), "app-lib");
    }
}
