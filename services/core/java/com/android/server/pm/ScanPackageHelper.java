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

import static android.content.pm.PackageManager.INSTALL_FAILED_DUPLICATE_PACKAGE;
import static android.content.pm.PackageManager.INSTALL_FAILED_INVALID_APK;
import static android.content.pm.PackageManager.INSTALL_FAILED_INVALID_INSTALL_LOCATION;
import static android.content.pm.PackageManager.INSTALL_FAILED_PACKAGE_CHANGED;
import static android.content.pm.PackageManager.INSTALL_FAILED_PROCESS_NOT_DEFINED;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_NO_CERTIFICATES;
import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;
import static android.os.incremental.IncrementalManager.isIncrementalPath;

import static com.android.server.pm.InstructionSets.getAppDexInstructionSets;
import static com.android.server.pm.PackageManagerService.DEBUG_ABI_SELECTION;
import static com.android.server.pm.PackageManagerService.DEBUG_INSTALL;
import static com.android.server.pm.PackageManagerService.DEBUG_PACKAGE_SCANNING;
import static com.android.server.pm.PackageManagerService.DEBUG_UPGRADE;
import static com.android.server.pm.PackageManagerService.DEBUG_VERIFY;
import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;
import static com.android.server.pm.PackageManagerService.SCAN_AS_FULL_APP;
import static com.android.server.pm.PackageManagerService.SCAN_AS_INSTANT_APP;
import static com.android.server.pm.PackageManagerService.SCAN_AS_ODM;
import static com.android.server.pm.PackageManagerService.SCAN_AS_OEM;
import static com.android.server.pm.PackageManagerService.SCAN_AS_PRIVILEGED;
import static com.android.server.pm.PackageManagerService.SCAN_AS_PRODUCT;
import static com.android.server.pm.PackageManagerService.SCAN_AS_SYSTEM;
import static com.android.server.pm.PackageManagerService.SCAN_AS_SYSTEM_EXT;
import static com.android.server.pm.PackageManagerService.SCAN_AS_VENDOR;
import static com.android.server.pm.PackageManagerService.SCAN_AS_VIRTUAL_PRELOAD;
import static com.android.server.pm.PackageManagerService.SCAN_BOOTING;
import static com.android.server.pm.PackageManagerService.SCAN_FIRST_BOOT_OR_UPGRADE;
import static com.android.server.pm.PackageManagerService.SCAN_MOVE;
import static com.android.server.pm.PackageManagerService.SCAN_NEW_INSTALL;
import static com.android.server.pm.PackageManagerService.SCAN_NO_DEX;
import static com.android.server.pm.PackageManagerService.SCAN_REQUIRE_KNOWN;
import static com.android.server.pm.PackageManagerService.SCAN_UPDATE_SIGNATURE;
import static com.android.server.pm.PackageManagerService.SCAN_UPDATE_TIME;
import static com.android.server.pm.PackageManagerService.TAG;
import static com.android.server.pm.PackageManagerServiceUtils.comparePackageSignatures;
import static com.android.server.pm.PackageManagerServiceUtils.compareSignatures;
import static com.android.server.pm.PackageManagerServiceUtils.compressedFileExists;
import static com.android.server.pm.PackageManagerServiceUtils.deriveAbiOverride;
import static com.android.server.pm.PackageManagerServiceUtils.getLastModifiedTime;
import static com.android.server.pm.PackageManagerServiceUtils.logCriticalInfo;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.SigningDetails;
import android.content.pm.parsing.ParsingPackageUtils;
import android.content.pm.parsing.component.ParsedActivity;
import android.content.pm.parsing.component.ParsedMainComponent;
import android.content.pm.parsing.component.ParsedProcess;
import android.content.pm.parsing.component.ParsedProvider;
import android.content.pm.parsing.component.ParsedService;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.os.Build;
import android.os.Process;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.apk.ApkSignatureVerifier;
import android.util.jar.StrictJarFile;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.security.VerityUtils;
import com.android.internal.util.ArrayUtils;
import com.android.server.SystemConfig;
import com.android.server.pm.parsing.PackageInfoUtils;
import com.android.server.pm.parsing.PackageParser2;
import com.android.server.pm.parsing.library.PackageBackwardCompatibility;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.pm.parsing.pkg.ParsedPackage;
import com.android.server.utils.WatchedLongSparseArray;

import dalvik.system.VMRuntime;

import java.io.File;
import java.io.IOException;
import java.security.DigestException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Helper class that handles package scanning logic
 */
public final class ScanPackageHelper {
    final PackageManagerService mPm;

    // TODO(b/198166813): remove PMS dependency
    public ScanPackageHelper(PackageManagerService pm) {
        mPm = pm;
    }

    /**
     * Prepares the system to commit a {@link ScanResult} in a way that will not fail by registering
     * the app ID required for reconcile.
     * @return {@code true} if a new app ID was registered and will need to be cleaned up on
     *         failure.
     */
    public boolean optimisticallyRegisterAppId(@NonNull ScanResult result)
            throws PackageManagerException {
        if (!result.mExistingSettingCopied || result.needsNewAppId()) {
            synchronized (mPm.mLock) {
                // THROWS: when we can't allocate a user id. add call to check if there's
                // enough space to ensure we won't throw; otherwise, don't modify state
                return mPm.mSettings.registerAppIdLPw(result.mPkgSetting, result.needsNewAppId());
            }
        }
        return false;
    }

    /**
     * Reverts any app ID creation that were made by
     * {@link #optimisticallyRegisterAppId(ScanResult)}. Note: this is only necessary if the
     * referenced method returned true.
     */
    public void cleanUpAppIdCreation(@NonNull ScanResult result) {
        // iff we've acquired an app ID for a new package setting, remove it so that it can be
        // acquired by another request.
        if (result.mPkgSetting.appId > 0) {
            mPm.mSettings.removeAppIdLPw(result.mPkgSetting.appId);
        }
    }

    /**
     *  Traces a package scan.
     *  @see #scanPackageLI(File, int, int, long, UserHandle)
     */
    @GuardedBy({"mPm.mInstallLock", "mPm.mLock"})
    public AndroidPackage scanPackageTracedLI(File scanFile, final int parseFlags,
            int scanFlags, long currentTime, UserHandle user) throws PackageManagerException {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "scanPackage [" + scanFile.toString() + "]");
        try {
            return scanPackageLI(scanFile, parseFlags, scanFlags, currentTime, user);
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    /**
     *  Similar to the other scanPackageTracedLI but accepting a ParsedPackage instead of a File.
     */
    @GuardedBy({"mPm.mInstallLock", "mPm.mLock"})
    public ScanResult scanPackageTracedLI(ParsedPackage parsedPackage,
            final @ParsingPackageUtils.ParseFlags int parseFlags,
            @PackageManagerService.ScanFlags int scanFlags, long currentTime,
            @Nullable UserHandle user, String cpuAbiOverride) throws PackageManagerException {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "scanPackage");
        try {
            return scanPackageNewLI(parsedPackage, parseFlags, scanFlags, currentTime, user,
                    cpuAbiOverride);
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    /**
     *  Scans a package and returns the newly parsed package.
     *  Returns {@code null} in case of errors and the error code is stored in mLastScanError
     */
    @GuardedBy({"mPm.mInstallLock", "mPm.mLock"})
    private AndroidPackage scanPackageLI(File scanFile, int parseFlags, int scanFlags,
            long currentTime, UserHandle user) throws PackageManagerException {
        if (DEBUG_INSTALL) Slog.d(TAG, "Parsing: " + scanFile);

        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "parsePackage");
        final ParsedPackage parsedPackage;
        try (PackageParser2 pp = mPm.mInjector.getScanningPackageParser()) {
            parsedPackage = pp.parsePackage(scanFile, parseFlags, false);
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }

        // Static shared libraries have synthetic package names
        if (parsedPackage.isStaticSharedLibrary()) {
            PackageManagerService.renameStaticSharedLibraryPackage(parsedPackage);
        }

        return addForInitLI(parsedPackage, parseFlags, scanFlags, currentTime, user,
                mPm.mPlatformPackage, mPm.mIsUpgrade, mPm.mIsPreNMR1Upgrade);
    }

    // TODO(b/199937291): scanPackageNewLI() and scanPackageOnlyLI() should be merged.
    // But, first, committing the results / removing app data needs to be moved up a level to the
    // callers of this method. Also, we need to solve the problem of potentially creating a new
    // shared user setting. That can probably be done later and patch things up after the fact.
    @GuardedBy({"mPm.mInstallLock", "mPm.mLock"})
    private ScanResult scanPackageNewLI(@NonNull ParsedPackage parsedPackage,
            final @ParsingPackageUtils.ParseFlags int parseFlags,
            @PackageManagerService.ScanFlags int scanFlags, long currentTime,
            @Nullable UserHandle user, String cpuAbiOverride) throws PackageManagerException {

        final String renamedPkgName = mPm.mSettings.getRenamedPackageLPr(
                AndroidPackageUtils.getRealPackageOrNull(parsedPackage));
        final String realPkgName = getRealPackageName(parsedPackage, renamedPkgName);
        if (realPkgName != null) {
            ensurePackageRenamed(parsedPackage, renamedPkgName);
        }
        final PackageSetting originalPkgSetting = getOriginalPackageLocked(parsedPackage,
                renamedPkgName);
        final PackageSetting pkgSetting =
                mPm.mSettings.getPackageLPr(parsedPackage.getPackageName());
        final PackageSetting disabledPkgSetting =
                mPm.mSettings.getDisabledSystemPkgLPr(parsedPackage.getPackageName());

        if (mPm.mTransferredPackages.contains(parsedPackage.getPackageName())) {
            Slog.w(TAG, "Package " + parsedPackage.getPackageName()
                    + " was transferred to another, but its .apk remains");
        }

        scanFlags = adjustScanFlags(scanFlags, pkgSetting, disabledPkgSetting, user, parsedPackage);
        synchronized (mPm.mLock) {
            boolean isUpdatedSystemApp;
            if (pkgSetting != null) {
                isUpdatedSystemApp = pkgSetting.getPkgState().isUpdatedSystemApp();
            } else {
                isUpdatedSystemApp = disabledPkgSetting != null;
            }
            applyPolicy(parsedPackage, scanFlags, mPm.mPlatformPackage, isUpdatedSystemApp);
            assertPackageIsValid(parsedPackage, parseFlags, scanFlags);

            SharedUserSetting sharedUserSetting = null;
            if (parsedPackage.getSharedUserId() != null) {
                // SIDE EFFECTS; may potentially allocate a new shared user
                sharedUserSetting = mPm.mSettings.getSharedUserLPw(parsedPackage.getSharedUserId(),
                        0 /*pkgFlags*/, 0 /*pkgPrivateFlags*/, true /*create*/);
                if (DEBUG_PACKAGE_SCANNING) {
                    if ((parseFlags & ParsingPackageUtils.PARSE_CHATTY) != 0) {
                        Log.d(TAG, "Shared UserID " + parsedPackage.getSharedUserId()
                                + " (uid=" + sharedUserSetting.userId + "):"
                                + " packages=" + sharedUserSetting.packages);
                    }
                }
            }
            String platformPackageName = mPm.mPlatformPackage == null
                    ? null : mPm.mPlatformPackage.getPackageName();
            final ScanRequest request = new ScanRequest(parsedPackage, sharedUserSetting,
                    pkgSetting == null ? null : pkgSetting.pkg, pkgSetting, disabledPkgSetting,
                    originalPkgSetting, realPkgName, parseFlags, scanFlags,
                    Objects.equals(parsedPackage.getPackageName(), platformPackageName), user,
                    cpuAbiOverride);
            return scanPackageOnlyLI(request, mPm.mInjector, mPm.mFactoryTest, currentTime);
        }
    }

    /**
     * Just scans the package without any side effects.
     * <p>Not entirely true at the moment. There is still one side effect -- this
     * method potentially modifies a live {@link PackageSetting} object representing
     * the package being scanned. This will be resolved in the future.
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
    public ScanResult scanPackageOnlyLI(@NonNull ScanRequest request,
            PackageManagerService.Injector injector,
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
        if (!needToDeriveAbi) {
            if (pkgSetting != null) {
                // TODO(b/154610922): if it is not first boot or upgrade, we should directly use
                // API info from existing package setting. However, stub packages currently do not
                // preserve ABI info, thus the special condition check here. Remove the special
                // check after we fix the stub generation.
                if (pkgSetting.pkg != null && pkgSetting.pkg.isStub()) {
                    needToDeriveAbi = true;
                } else {
                    primaryCpuAbiFromSettings = pkgSetting.primaryCpuAbiString;
                    secondaryCpuAbiFromSettings = pkgSetting.secondaryCpuAbiString;
                }
            } else {
                // Re-scanning a system package after uninstalling updates; need to derive ABI
                needToDeriveAbi = true;
            }
        }

        int previousAppId = Process.INVALID_UID;

        if (pkgSetting != null && pkgSetting.sharedUser != sharedUserSetting) {
            if (pkgSetting.sharedUser != null && sharedUserSetting == null) {
                previousAppId = pkgSetting.appId;
                // Log that something is leaving shareduid and keep going
                Slog.i(TAG,
                        "Package " + parsedPackage.getPackageName() + " shared user changed from "
                                + pkgSetting.sharedUser.name + " to " + "<nothing>.");
            } else {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Package " + parsedPackage.getPackageName() + " shared user changed from "
                                + (pkgSetting.sharedUser != null
                                ? pkgSetting.sharedUser.name : "<nothing>")
                                + " to "
                                + (sharedUserSetting != null ? sharedUserSetting.name : "<nothing>")
                                + "; replacing with new");
                pkgSetting = null;
            }
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
                    true /*allowInstall*/, instantApp, virtualPreload,
                    UserManagerService.getInstance(), usesStaticLibraries,
                    parsedPackage.getUsesStaticLibrariesVersions(), parsedPackage.getMimeGroups(),
                    newDomainSetId);
        } else {
            // make a deep copy to avoid modifying any existing system state.
            pkgSetting = new PackageSetting(pkgSetting);
            pkgSetting.pkg = parsedPackage;

            // REMOVE SharedUserSetting from method; update in a separate call.
            //
            // TODO(narayan): This update is bogus. nativeLibraryDir & primaryCpuAbi,
            // secondaryCpuAbi are not known at this point so we always update them
            // to null here, only to reset them at a later point.
            Settings.updatePackageSetting(pkgSetting, disabledPkgSetting, sharedUserSetting,
                    destCodeFile, parsedPackage.getNativeLibraryDir(),
                    AndroidPackageUtils.getPrimaryCpuAbi(parsedPackage, pkgSetting),
                    AndroidPackageUtils.getSecondaryCpuAbi(parsedPackage, pkgSetting),
                    PackageInfoUtils.appInfoFlags(parsedPackage, pkgSetting),
                    PackageInfoUtils.appInfoPrivateFlags(parsedPackage, pkgSetting),
                    UserManagerService.getInstance(),
                    usesStaticLibraries, parsedPackage.getUsesStaticLibrariesVersions(),
                    parsedPackage.getMimeGroups(), newDomainSetId);
        }
        if (createNewPackage && originalPkgSetting != null) {
            // This is the initial transition from the original package, so,
            // fix up the new package's name now. We must do this after looking
            // up the package under its new name, so getPackageLP takes care of
            // fiddling things correctly.
            parsedPackage.setPackageName(originalPkgSetting.name);

            // File a report about this.
            String msg = "New package " + pkgSetting.realName
                    + " renamed to replace old package " + pkgSetting.name;
            PackageManagerService.reportSettingsProblem(Log.WARN, msg);
        }

        final int userId = (user == null ? UserHandle.USER_SYSTEM : user.getIdentifier());
        // for existing packages, change the install state; but, only if it's explicitly specified
        if (!createNewPackage) {
            final boolean instantApp = (scanFlags & SCAN_AS_INSTANT_APP) != 0;
            final boolean fullApp = (scanFlags & SCAN_AS_FULL_APP) != 0;
            PackageManagerService.setInstantAppForUser(
                    injector, pkgSetting, userId, instantApp, fullApp);
        }
        // TODO(patb): see if we can do away with disabled check here.
        if (disabledPkgSetting != null
                || (0 != (scanFlags & SCAN_NEW_INSTALL)
                && pkgSetting != null && pkgSetting.isSystem())) {
            pkgSetting.getPkgState().setUpdatedSystemApp(true);
        }

        parsedPackage.setSeInfo(SELinuxMMAC.getSeInfo(parsedPackage, sharedUserSetting,
                injector.getCompatibility()));

        if (parsedPackage.isSystem()) {
            configurePackageComponents(parsedPackage);
        }

        final String cpuAbiOverride = deriveAbiOverride(request.mCpuAbiOverride);
        final boolean isUpdatedSystemApp = pkgSetting.getPkgState().isUpdatedSystemApp();

        final File appLib32InstallDir = PackageManagerService.getAppLib32InstallDir();
        if ((scanFlags & SCAN_NEW_INSTALL) == 0) {
            if (needToDeriveAbi) {
                Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "derivePackageAbi");
                final Pair<PackageAbiHelper.Abis, PackageAbiHelper.NativeLibraryPaths> derivedAbi =
                        packageAbiHelper.derivePackageAbi(parsedPackage, isUpdatedSystemApp,
                                cpuAbiOverride, appLib32InstallDir);
                derivedAbi.first.applyTo(parsedPackage);
                derivedAbi.second.applyTo(parsedPackage);
                Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);

                // Some system apps still use directory structure for native libraries
                // in which case we might end up not detecting abi solely based on apk
                // structure. Try to detect abi based on directory structure.

                String pkgRawPrimaryCpuAbi = AndroidPackageUtils.getRawPrimaryCpuAbi(parsedPackage);
                if (parsedPackage.isSystem() && !isUpdatedSystemApp
                        && pkgRawPrimaryCpuAbi == null) {
                    final PackageAbiHelper.Abis abis = packageAbiHelper.getBundledAppAbis(
                            parsedPackage);
                    abis.applyTo(parsedPackage);
                    abis.applyTo(pkgSetting);
                    final PackageAbiHelper.NativeLibraryPaths nativeLibraryPaths =
                            packageAbiHelper.deriveNativeLibraryPaths(parsedPackage,
                                    isUpdatedSystemApp, appLib32InstallDir);
                    nativeLibraryPaths.applyTo(parsedPackage);
                }
            } else {
                // This is not a first boot or an upgrade, don't bother deriving the
                // ABI during the scan. Instead, trust the value that was stored in the
                // package setting.
                parsedPackage.setPrimaryCpuAbi(primaryCpuAbiFromSettings)
                        .setSecondaryCpuAbi(secondaryCpuAbiFromSettings);

                final PackageAbiHelper.NativeLibraryPaths nativeLibraryPaths =
                        packageAbiHelper.deriveNativeLibraryPaths(parsedPackage,
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
                // We haven't run dex-opt for this move (since we've moved the compiled output too)
                // but we already have this packages package info in the PackageSetting. We just
                // use that and derive the native library path based on the new code path.
                parsedPackage.setPrimaryCpuAbi(pkgSetting.primaryCpuAbiString)
                        .setSecondaryCpuAbi(pkgSetting.secondaryCpuAbiString);
            }

            // Set native library paths again. For moves, the path will be updated based on the
            // ABIs we've determined above. For non-moves, the path will be updated based on the
            // ABIs we determined during compilation, but the path will depend on the final
            // package path (after the rename away from the stage path).
            final PackageAbiHelper.NativeLibraryPaths nativeLibraryPaths =
                    packageAbiHelper.deriveNativeLibraryPaths(parsedPackage, isUpdatedSystemApp,
                            appLib32InstallDir);
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

        pkgSetting.primaryCpuAbiString = AndroidPackageUtils.getRawPrimaryCpuAbi(parsedPackage);
        pkgSetting.secondaryCpuAbiString = AndroidPackageUtils.getRawSecondaryCpuAbi(parsedPackage);
        pkgSetting.cpuAbiOverrideString = cpuAbiOverride;

        if (DEBUG_ABI_SELECTION) {
            Slog.d(TAG, "Resolved nativeLibraryRoot for " + parsedPackage.getPackageName()
                    + " to root=" + parsedPackage.getNativeLibraryRootDir()
                    + ", to dir=" + parsedPackage.getNativeLibraryDir()
                    + ", isa=" + parsedPackage.isNativeLibraryRootRequiresIsa());
        }

        // Push the derived path down into PackageSettings so we know what to
        // clean up at uninstall time.
        pkgSetting.legacyNativeLibraryPathString = parsedPackage.getNativeLibraryRootDir();

        if (DEBUG_ABI_SELECTION) {
            Log.d(TAG, "Abis for package[" + parsedPackage.getPackageName() + "] are"
                    + " primary=" + pkgSetting.primaryCpuAbiString
                    + " secondary=" + pkgSetting.primaryCpuAbiString
                    + " abiOverride=" + pkgSetting.cpuAbiOverrideString);
        }

        if ((scanFlags & SCAN_BOOTING) == 0 && pkgSetting.sharedUser != null) {
            // We don't do this here during boot because we can do it all
            // at once after scanning all existing packages.
            //
            // We also do this *before* we perform dexopt on this package, so that
            // we can avoid redundant dexopts, and also to make sure we've got the
            // code and package path correct.
            changedAbiCodePath = applyAdjustedAbiToSharedUser(pkgSetting.sharedUser, parsedPackage,
                    packageAbiHelper.getAdjustedAbiForSharedUser(
                            pkgSetting.sharedUser.packages, parsedPackage));
        }

        parsedPackage.setFactoryTest(isUnderFactoryTest && parsedPackage.getRequestedPermissions()
                .contains(android.Manifest.permission.FACTORY_TEST));

        if (parsedPackage.isSystem()) {
            pkgSetting.setIsOrphaned(true);
        }

        // Take care of first install / last update times.
        final long scanFileTime = getLastModifiedTime(parsedPackage);
        if (currentTime != 0) {
            if (pkgSetting.firstInstallTime == 0) {
                pkgSetting.firstInstallTime = pkgSetting.lastUpdateTime = currentTime;
            } else if ((scanFlags & SCAN_UPDATE_TIME) != 0) {
                pkgSetting.lastUpdateTime = currentTime;
            }
        } else if (pkgSetting.firstInstallTime == 0) {
            // We need *something*.  Take time time stamp of the file.
            pkgSetting.firstInstallTime = pkgSetting.lastUpdateTime = scanFileTime;
        } else if ((parseFlags & ParsingPackageUtils.PARSE_IS_SYSTEM_DIR) != 0) {
            if (scanFileTime != pkgSetting.timeStamp) {
                // A package on the system image has changed; consider this
                // to be an update.
                pkgSetting.lastUpdateTime = scanFileTime;
            }
        }
        pkgSetting.setTimeStamp(scanFileTime);
        // TODO(b/135203078): Remove, move to constructor
        pkgSetting.pkg = parsedPackage;
        pkgSetting.pkgFlags = PackageInfoUtils.appInfoFlags(parsedPackage, pkgSetting);
        pkgSetting.pkgPrivateFlags =
                PackageInfoUtils.appInfoPrivateFlags(parsedPackage, pkgSetting);
        if (parsedPackage.getLongVersionCode() != pkgSetting.versionCode) {
            pkgSetting.versionCode = parsedPackage.getLongVersionCode();
        }
        // Update volume if needed
        final String volumeUuid = parsedPackage.getVolumeUuid();
        if (!Objects.equals(volumeUuid, pkgSetting.volumeUuid)) {
            Slog.i(PackageManagerService.TAG,
                    "Update" + (pkgSetting.isSystem() ? " system" : "")
                            + " package " + parsedPackage.getPackageName()
                            + " volume from " + pkgSetting.volumeUuid
                            + " to " + volumeUuid);
            pkgSetting.volumeUuid = volumeUuid;
        }

        SharedLibraryInfo staticSharedLibraryInfo = null;
        if (!TextUtils.isEmpty(parsedPackage.getStaticSharedLibName())) {
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

        return new ScanResult(request, true, pkgSetting, changedAbiCodePath,
                !createNewPackage /* existingSettingCopied */,
                previousAppId, staticSharedLibraryInfo,
                dynamicSharedLibraryInfos);
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
    private @PackageManagerService.ScanFlags int adjustScanFlags(
            @PackageManagerService.ScanFlags int scanFlags,
            PackageSetting pkgSetting, PackageSetting disabledPkgSetting, UserHandle user,
            AndroidPackage pkg) {

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
            if ((systemPkgSetting.pkgPrivateFlags
                    & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0) {
                scanFlags |= SCAN_AS_PRIVILEGED;
            }
            if ((systemPkgSetting.pkgPrivateFlags
                    & ApplicationInfo.PRIVATE_FLAG_OEM) != 0) {
                scanFlags |= SCAN_AS_OEM;
            }
            if ((systemPkgSetting.pkgPrivateFlags
                    & ApplicationInfo.PRIVATE_FLAG_VENDOR) != 0) {
                scanFlags |= SCAN_AS_VENDOR;
            }
            if ((systemPkgSetting.pkgPrivateFlags
                    & ApplicationInfo.PRIVATE_FLAG_PRODUCT) != 0) {
                scanFlags |= SCAN_AS_PRODUCT;
            }
            if ((systemPkgSetting.pkgPrivateFlags
                    & ApplicationInfo.PRIVATE_FLAG_SYSTEM_EXT) != 0) {
                scanFlags |= SCAN_AS_SYSTEM_EXT;
            }
            if ((systemPkgSetting.pkgPrivateFlags
                    & ApplicationInfo.PRIVATE_FLAG_ODM) != 0) {
                scanFlags |= SCAN_AS_ODM;
            }
        }
        if (pkgSetting != null) {
            final int userId = ((user == null) ? 0 : user.getIdentifier());
            if (pkgSetting.getInstantApp(userId)) {
                scanFlags |= SCAN_AS_INSTANT_APP;
            }
            if (pkgSetting.getVirtulalPreload(userId)) {
                scanFlags |= SCAN_AS_VIRTUAL_PRELOAD;
            }
        }

        // Scan as privileged apps that share a user with a priv-app.
        final boolean skipVendorPrivilegeScan = ((scanFlags & SCAN_AS_VENDOR) != 0)
                && getVendorPartitionVersion() < 28;
        if (((scanFlags & SCAN_AS_PRIVILEGED) == 0)
                && !pkg.isPrivileged()
                && (pkg.getSharedUserId() != null)
                && !skipVendorPrivilegeScan) {
            SharedUserSetting sharedUserSetting = null;
            try {
                sharedUserSetting = mPm.mSettings.getSharedUserLPw(pkg.getSharedUserId(), 0,
                        0, false);
            } catch (PackageManagerException ignore) {
            }
            if (sharedUserSetting != null && sharedUserSetting.isPrivileged()) {
                // Exempt SharedUsers signed with the platform key.
                // TODO(b/72378145) Fix this exemption. Force signature apps
                // to allowlist their privileged permissions just like other
                // priv-apps.
                synchronized (mPm.mLock) {
                    PackageSetting platformPkgSetting = mPm.mSettings.getPackageLPr("android");
                    if ((compareSignatures(
                            platformPkgSetting.signatures.mSigningDetails.getSignatures(),
                            pkg.getSigningDetails().getSignatures())
                            != PackageManager.SIGNATURE_MATCH)) {
                        scanFlags |= SCAN_AS_PRIVILEGED;
                    }
                }
            }
        }

        return scanFlags;
    }


    /**
     * Adds a new package to the internal data structures during platform initialization.
     * <p>After adding, the package is known to the system and available for querying.
     * <p>For packages located on the device ROM [eg. packages located in /system, /vendor,
     * etc...], additional checks are performed. Basic verification [such as ensuring
     * matching signatures, checking version codes, etc...] occurs if the package is
     * identical to a previously known package. If the package fails a signature check,
     * the version installed on /data will be removed. If the version of the new package
     * is less than or equal than the version on /data, it will be ignored.
     * <p>Regardless of the package location, the results are applied to the internal
     * structures and the package is made available to the rest of the system.
     * <p>NOTE: The return value should be removed. It's the passed in package object.
     */
    @GuardedBy({"mPm.mLock", "mPm.mInstallLock"})
    public AndroidPackage addForInitLI(ParsedPackage parsedPackage,
            @ParsingPackageUtils.ParseFlags int parseFlags,
            @PackageManagerService.ScanFlags int scanFlags, long currentTime,
            @Nullable UserHandle user, AndroidPackage platformPackage, boolean isUpgrade,
            boolean isPreNMR1Upgrade) throws PackageManagerException {
        final boolean scanSystemPartition =
                (parseFlags & ParsingPackageUtils.PARSE_IS_SYSTEM_DIR) != 0;
        final String renamedPkgName;
        final PackageSetting disabledPkgSetting;
        final boolean isSystemPkgUpdated;
        final boolean pkgAlreadyExists;
        PackageSetting pkgSetting;

        synchronized (mPm.mLock) {
            renamedPkgName = mPm.mSettings.getRenamedPackageLPr(
                    AndroidPackageUtils.getRealPackageOrNull(parsedPackage));
            final String realPkgName = getRealPackageName(parsedPackage,
                    renamedPkgName);
            if (realPkgName != null) {
                ensurePackageRenamed(parsedPackage, renamedPkgName);
            }
            final PackageSetting originalPkgSetting = getOriginalPackageLocked(parsedPackage,
                    renamedPkgName);
            final PackageSetting installedPkgSetting = mPm.mSettings.getPackageLPr(
                    parsedPackage.getPackageName());
            pkgSetting = originalPkgSetting == null ? installedPkgSetting : originalPkgSetting;
            pkgAlreadyExists = pkgSetting != null;
            final String disabledPkgName = pkgAlreadyExists
                    ? pkgSetting.name : parsedPackage.getPackageName();
            if (scanSystemPartition && !pkgAlreadyExists
                    && mPm.mSettings.getDisabledSystemPkgLPr(disabledPkgName) != null) {
                // The updated-package data for /system apk remains inconsistently
                // after the package data for /data apk is lost accidentally.
                // To recover it, enable /system apk and install it as non-updated system app.
                Slog.w(TAG, "Inconsistent package setting of updated system app for "
                        + disabledPkgName + ". To recover it, enable the system app"
                        + "and install it as non-updated system app.");
                mPm.mSettings.removeDisabledSystemPackageLPw(disabledPkgName);
            }
            disabledPkgSetting = mPm.mSettings.getDisabledSystemPkgLPr(disabledPkgName);
            isSystemPkgUpdated = disabledPkgSetting != null;

            if (DEBUG_INSTALL && isSystemPkgUpdated) {
                Slog.d(TAG, "updatedPkg = " + disabledPkgSetting);
            }

            final SharedUserSetting sharedUserSetting = (parsedPackage.getSharedUserId() != null)
                    ? mPm.mSettings.getSharedUserLPw(parsedPackage.getSharedUserId(),
                    0 /*pkgFlags*/, 0 /*pkgPrivateFlags*/, true)
                    : null;
            if (DEBUG_PACKAGE_SCANNING
                    && (parseFlags & ParsingPackageUtils.PARSE_CHATTY) != 0
                    && sharedUserSetting != null) {
                Log.d(TAG, "Shared UserID " + parsedPackage.getSharedUserId()
                        + " (uid=" + sharedUserSetting.userId + "):"
                        + " packages=" + sharedUserSetting.packages);
            }

            if (scanSystemPartition) {
                if (isSystemPkgUpdated) {
                    // we're updating the disabled package, so, scan it as the package setting
                    boolean isPlatformPackage = platformPackage != null
                            && Objects.equals(platformPackage.getPackageName(),
                            parsedPackage.getPackageName());
                    final ScanRequest request = new ScanRequest(parsedPackage, sharedUserSetting,
                            null, disabledPkgSetting /* pkgSetting */,
                            null /* disabledPkgSetting */, null /* originalPkgSetting */,
                            null, parseFlags, scanFlags, isPlatformPackage, user, null);
                    applyPolicy(parsedPackage, scanFlags,
                            platformPackage, true);
                    final ScanResult scanResult =
                            scanPackageOnlyLI(request, mPm.mInjector,
                                    mPm.mFactoryTest, -1L);
                    if (scanResult.mExistingSettingCopied
                            && scanResult.mRequest.mPkgSetting != null) {
                        scanResult.mRequest.mPkgSetting.updateFrom(scanResult.mPkgSetting);
                    }
                }
            }
        }

        final boolean newPkgChangedPaths = pkgAlreadyExists
                && !pkgSetting.getPathString().equals(parsedPackage.getPath());
        final boolean newPkgVersionGreater =
                pkgAlreadyExists && parsedPackage.getLongVersionCode() > pkgSetting.versionCode;
        final boolean isSystemPkgBetter = scanSystemPartition && isSystemPkgUpdated
                && newPkgChangedPaths && newPkgVersionGreater;
        if (isSystemPkgBetter) {
            // The version of the application on /system is greater than the version on
            // /data. Switch back to the application on /system.
            // It's safe to assume the application on /system will correctly scan. If not,
            // there won't be a working copy of the application.
            synchronized (mPm.mLock) {
                // just remove the loaded entries from package lists
                mPm.mPackages.remove(pkgSetting.name);
            }

            logCriticalInfo(Log.WARN,
                    "System package updated;"
                            + " name: " + pkgSetting.name
                            + "; " + pkgSetting.versionCode + " --> "
                            + parsedPackage.getLongVersionCode()
                            + "; " + pkgSetting.getPathString()
                            + " --> " + parsedPackage.getPath());

            final InstallArgs args = mPm.createInstallArgsForExisting(
                    pkgSetting.getPathString(), getAppDexInstructionSets(
                            pkgSetting.primaryCpuAbiString, pkgSetting.secondaryCpuAbiString));
            args.cleanUpResourcesLI();
            synchronized (mPm.mLock) {
                mPm.mSettings.enableSystemPackageLPw(pkgSetting.name);
            }
        }

        // The version of the application on the /system partition is less than or
        // equal to the version on the /data partition. Throw an exception and use
        // the application already installed on the /data partition.
        if (scanSystemPartition && isSystemPkgUpdated && !isSystemPkgBetter) {
            // In the case of a skipped package, commitReconciledScanResultLocked is not called to
            // add the object to the "live" data structures, so this is the final mutation step
            // for the package. Which means it needs to be finalized here to cache derived fields.
            // This is relevant for cases where the disabled system package is used for flags or
            // other metadata.
            parsedPackage.hideAsFinal();
            throw new PackageManagerException(Log.WARN, "Package " + parsedPackage.getPackageName()
                    + " at " + parsedPackage.getPath() + " ignored: updated version "
                    + (pkgAlreadyExists ? String.valueOf(pkgSetting.versionCode) : "unknown")
                    + " better than this " + parsedPackage.getLongVersionCode());
        }

        // Verify certificates against what was last scanned. Force re-collecting certificate in two
        // special cases:
        // 1) when scanning system, force re-collect only if system is upgrading.
        // 2) when scannning /data, force re-collect only if the app is privileged (updated from
        // preinstall, or treated as privileged, e.g. due to shared user ID).
        final boolean forceCollect = scanSystemPartition ? isUpgrade
                : PackageManagerServiceUtils.isApkVerificationForced(pkgSetting);
        if (DEBUG_VERIFY && forceCollect) {
            Slog.d(TAG, "Force collect certificate of " + parsedPackage.getPackageName());
        }

        // Full APK verification can be skipped during certificate collection, only if the file is
        // in verified partition, or can be verified on access (when apk verity is enabled). In both
        // cases, only data in Signing Block is verified instead of the whole file.
        // TODO(b/136132412): skip for Incremental installation
        final boolean skipVerify = scanSystemPartition
                || (forceCollect && canSkipForcedPackageVerification(parsedPackage));
        collectCertificatesLI(pkgSetting, parsedPackage, forceCollect, skipVerify,
                isPreNMR1Upgrade);

        // Reset profile if the application version is changed
        maybeClearProfilesForUpgradesLI(pkgSetting, parsedPackage);

        /*
         * A new system app appeared, but we already had a non-system one of the
         * same name installed earlier.
         */
        boolean shouldHideSystemApp = false;
        // A new application appeared on /system, but, we already have a copy of
        // the application installed on /data.
        if (scanSystemPartition && !isSystemPkgUpdated && pkgAlreadyExists
                && !pkgSetting.isSystem()) {

            if (!parsedPackage.getSigningDetails()
                    .checkCapability(pkgSetting.signatures.mSigningDetails,
                            SigningDetails.CertCapabilities.INSTALLED_DATA)
                    && !pkgSetting.signatures.mSigningDetails.checkCapability(
                    parsedPackage.getSigningDetails(),
                    SigningDetails.CertCapabilities.ROLLBACK)) {
                logCriticalInfo(Log.WARN,
                        "System package signature mismatch;"
                                + " name: " + pkgSetting.name);
                try (@SuppressWarnings("unused") PackageFreezer freezer = mPm.freezePackage(
                        parsedPackage.getPackageName(),
                        "scanPackageInternalLI")) {
                    mPm.deletePackageLIF(parsedPackage.getPackageName(), null, true,
                            mPm.mUserManager.getUserIds(), 0, null, false);
                }
                pkgSetting = null;
            } else if (newPkgVersionGreater) {
                // The application on /system is newer than the application on /data.
                // Simply remove the application on /data [keeping application data]
                // and replace it with the version on /system.
                logCriticalInfo(Log.WARN,
                        "System package enabled;"
                                + " name: " + pkgSetting.name
                                + "; " + pkgSetting.versionCode + " --> "
                                + parsedPackage.getLongVersionCode()
                                + "; " + pkgSetting.getPathString() + " --> "
                                + parsedPackage.getPath());
                InstallArgs args = mPm.createInstallArgsForExisting(
                        pkgSetting.getPathString(), getAppDexInstructionSets(
                                pkgSetting.primaryCpuAbiString, pkgSetting.secondaryCpuAbiString));
                synchronized (mPm.mInstallLock) {
                    args.cleanUpResourcesLI();
                }
            } else {
                // The application on /system is older than the application on /data. Hide
                // the application on /system and the version on /data will be scanned later
                // and re-added like an update.
                shouldHideSystemApp = true;
                logCriticalInfo(Log.INFO,
                        "System package disabled;"
                                + " name: " + pkgSetting.name
                                + "; old: " + pkgSetting.getPathString() + " @ "
                                + pkgSetting.versionCode
                                + "; new: " + parsedPackage.getPath() + " @ "
                                + parsedPackage.getPath());
            }
        }

        final ScanResult scanResult = scanPackageNewLI(parsedPackage, parseFlags, scanFlags
                | SCAN_UPDATE_SIGNATURE, currentTime, user, null);
        if (scanResult.mSuccess) {
            synchronized (mPm.mLock) {
                boolean appIdCreated = false;
                try {
                    final String pkgName = scanResult.mPkgSetting.name;
                    final Map<String, ReconciledPackage> reconcileResult =
                            mPm.reconcilePackagesLocked(
                                    new ReconcileRequest(
                                            Collections.singletonMap(pkgName, scanResult),
                                            mPm.mSharedLibraries,
                                            mPm.mPackages,
                                            Collections.singletonMap(
                                                    pkgName,
                                                    mPm.getSettingsVersionForPackage(
                                                            parsedPackage)),
                                            Collections.singletonMap(pkgName,
                                                    mPm.getSharedLibLatestVersionSetting(
                                                            scanResult))),
                                    mPm.mSettings.getKeySetManagerService(), mPm.mInjector);
                    appIdCreated = optimisticallyRegisterAppId(scanResult);
                    mPm.commitReconciledScanResultLocked(
                            reconcileResult.get(pkgName), mPm.mUserManager.getUserIds());
                } catch (PackageManagerException e) {
                    if (appIdCreated) {
                        cleanUpAppIdCreation(scanResult);
                    }
                    throw e;
                }
            }
        }

        if (shouldHideSystemApp) {
            synchronized (mPm.mLock) {
                mPm.mSettings.disableSystemPackageLPw(parsedPackage.getPackageName(), true);
            }
        }
        if (mPm.mIncrementalManager != null && isIncrementalPath(parsedPackage.getPath())) {
            if (pkgSetting != null && pkgSetting.isPackageLoading()) {
                // Continue monitoring loading progress of active incremental packages
                final IncrementalStatesCallback incrementalStatesCallback =
                        new IncrementalStatesCallback(parsedPackage.getPackageName(), mPm);
                pkgSetting.setIncrementalStatesCallback(incrementalStatesCallback);
                mPm.mIncrementalManager.registerLoadingProgressCallback(parsedPackage.getPath(),
                        new IncrementalProgressListener(parsedPackage.getPackageName(), mPm));
            }
        }
        return scanResult.mPkgSetting.pkg;
    }

    /**
     * Returns if forced apk verification can be skipped for the whole package, including splits.
     */
    private boolean canSkipForcedPackageVerification(AndroidPackage pkg) {
        if (!canSkipForcedApkVerification(pkg.getBaseApkPath())) {
            return false;
        }
        // TODO: Allow base and splits to be verified individually.
        String[] splitCodePaths = pkg.getSplitCodePaths();
        if (!ArrayUtils.isEmpty(splitCodePaths)) {
            for (int i = 0; i < splitCodePaths.length; i++) {
                if (!canSkipForcedApkVerification(splitCodePaths[i])) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Returns if forced apk verification can be skipped, depending on current FSVerity setup and
     * whether the apk contains signed root hash.  Note that the signer's certificate still needs to
     * match one in a trusted source, and should be done separately.
     */
    private boolean canSkipForcedApkVerification(String apkPath) {
        if (!PackageManagerServiceUtils.isLegacyApkVerityEnabled()) {
            return VerityUtils.hasFsverity(apkPath);
        }

        try {
            final byte[] rootHashObserved = VerityUtils.generateApkVerityRootHash(apkPath);
            if (rootHashObserved == null) {
                return false;  // APK does not contain Merkle tree root hash.
            }
            synchronized (mPm.mInstallLock) {
                // Returns whether the observed root hash matches what kernel has.
                mPm.mInstaller.assertFsverityRootHashMatches(apkPath, rootHashObserved);
                return true;
            }
        } catch (Installer.InstallerException | IOException | DigestException
                | NoSuchAlgorithmException e) {
            Slog.w(TAG, "Error in fsverity check. Fallback to full apk verification.", e);
        }
        return false;
    }

    private void collectCertificatesLI(PackageSetting ps, ParsedPackage parsedPackage,
            boolean forceCollect, boolean skipVerify, boolean mIsPreNMR1Upgrade)
            throws PackageManagerException {
        // When upgrading from pre-N MR1, verify the package time stamp using the package
        // directory and not the APK file.
        final long lastModifiedTime = mIsPreNMR1Upgrade
                ? new File(parsedPackage.getPath()).lastModified()
                : getLastModifiedTime(parsedPackage);
        final Settings.VersionInfo settingsVersionForPackage =
                mPm.getSettingsVersionForPackage(parsedPackage);
        if (ps != null && !forceCollect
                && ps.getPathString().equals(parsedPackage.getPath())
                && ps.timeStamp == lastModifiedTime
                && !PackageManagerService.isCompatSignatureUpdateNeeded(settingsVersionForPackage)
                && !PackageManagerService.isRecoverSignatureUpdateNeeded(
                settingsVersionForPackage)) {
            if (ps.signatures.mSigningDetails.getSignatures() != null
                    && ps.signatures.mSigningDetails.getSignatures().length != 0
                    && ps.signatures.mSigningDetails.getSignatureSchemeVersion()
                    != SigningDetails.SignatureSchemeVersion.UNKNOWN) {
                // Optimization: reuse the existing cached signing data
                // if the package appears to be unchanged.
                parsedPackage.setSigningDetails(
                        new SigningDetails(ps.signatures.mSigningDetails));
                return;
            }

            Slog.w(TAG, "PackageSetting for " + ps.name
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

    /**
     * Clear the package profile if this was an upgrade and the package
     * version was updated.
     */
    private void maybeClearProfilesForUpgradesLI(
            @Nullable PackageSetting originalPkgSetting,
            @NonNull AndroidPackage pkg) {
        if (originalPkgSetting == null || !mPm.isDeviceUpgrading()) {
            return;
        }
        if (originalPkgSetting.versionCode == pkg.getLongVersionCode()) {
            return;
        }

        mPm.clearAppProfilesLIF(pkg);
        if (DEBUG_INSTALL) {
            Slog.d(TAG, originalPkgSetting.name
                    + " clear profile due to version change "
                    + originalPkgSetting.versionCode + " != "
                    + pkg.getLongVersionCode());
        }
    }

    /**
     * Returns the original package setting.
     * <p>A package can migrate its name during an update. In this scenario, a package
     * designates a set of names that it considers as one of its original names.
     * <p>An original package must be signed identically and it must have the same
     * shared user [if any].
     */
    @GuardedBy("mPm.mLock")
    @Nullable
    private PackageSetting getOriginalPackageLocked(@NonNull AndroidPackage pkg,
            @Nullable String renamedPkgName) {
        if (isPackageRenamed(pkg, renamedPkgName)) {
            return null;
        }
        for (int i = ArrayUtils.size(pkg.getOriginalPackages()) - 1; i >= 0; --i) {
            final PackageSetting originalPs =
                    mPm.mSettings.getPackageLPr(pkg.getOriginalPackages().get(i));
            if (originalPs != null) {
                // the package is already installed under its original name...
                // but, should we use it?
                if (!verifyPackageUpdateLPr(originalPs, pkg)) {
                    // the new package is incompatible with the original
                    continue;
                } else if (originalPs.sharedUser != null) {
                    if (!originalPs.sharedUser.name.equals(pkg.getSharedUserId())) {
                        // the shared user id is incompatible with the original
                        Slog.w(TAG, "Unable to migrate data from " + originalPs.name
                                + " to " + pkg.getPackageName() + ": old uid "
                                + originalPs.sharedUser.name
                                + " differs from " + pkg.getSharedUserId());
                        continue;
                    }
                    // TODO: Add case when shared user id is added [b/28144775]
                } else {
                    if (DEBUG_UPGRADE) {
                        Log.v(TAG, "Renaming new package "
                                + pkg.getPackageName() + " to old name " + originalPs.name);
                    }
                }
                return originalPs;
            }
        }
        return null;
    }

    @GuardedBy("mPm.mLock")
    private boolean verifyPackageUpdateLPr(PackageSetting oldPkg, AndroidPackage newPkg) {
        if ((oldPkg.pkgFlags & ApplicationInfo.FLAG_SYSTEM) == 0) {
            Slog.w(TAG, "Unable to update from " + oldPkg.name
                    + " to " + newPkg.getPackageName()
                    + ": old package not in system partition");
            return false;
        } else if (mPm.mPackages.get(oldPkg.name) != null) {
            Slog.w(TAG, "Unable to update from " + oldPkg.name
                    + " to " + newPkg.getPackageName()
                    + ": old package still exists");
            return false;
        }
        return true;
    }

    /**
     * Asserts the parsed package is valid according to the given policy. If the
     * package is invalid, for whatever reason, throws {@link PackageManagerException}.
     * <p>
     * Implementation detail: This method must NOT have any side effects. It would
     * ideally be static, but, it requires locks to read system state.
     *
     * @throws PackageManagerException If the package fails any of the validation checks
     */
    private void assertPackageIsValid(AndroidPackage pkg,
            final @ParsingPackageUtils.ParseFlags int parseFlags,
            final @PackageManagerService.ScanFlags int scanFlags)
            throws PackageManagerException {
        if ((parseFlags & ParsingPackageUtils.PARSE_ENFORCE_CODE) != 0) {
            assertCodePolicy(pkg);
        }

        if (pkg.getPath() == null) {
            // Bail out. The resource and code paths haven't been set.
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                    "Code and resource paths haven't been set correctly");
        }

        // Check that there is an APEX package with the same name only during install/first boot
        // after OTA.
        final boolean isUserInstall = (scanFlags & SCAN_BOOTING) == 0;
        final boolean isFirstBootOrUpgrade = (scanFlags & SCAN_FIRST_BOOT_OR_UPGRADE) != 0;
        if ((isUserInstall || isFirstBootOrUpgrade)
                && mPm.mApexManager.isApexPackage(pkg.getPackageName())) {
            throw new PackageManagerException(INSTALL_FAILED_DUPLICATE_PACKAGE,
                    pkg.getPackageName()
                            + " is an APEX package and can't be installed as an APK.");
        }

        // Make sure we're not adding any bogus keyset info
        final KeySetManagerService ksms = mPm.mSettings.getKeySetManagerService();
        ksms.assertScannedPackageValid(pkg);

        synchronized (mPm.mLock) {
            // The special "android" package can only be defined once
            if (pkg.getPackageName().equals("android")) {
                if (mPm.mAndroidApplication != null) {
                    Slog.w(TAG, "*************************************************");
                    Slog.w(TAG, "Core android package being redefined.  Skipping.");
                    Slog.w(TAG, " codePath=" + pkg.getPath());
                    Slog.w(TAG, "*************************************************");
                    throw new PackageManagerException(INSTALL_FAILED_DUPLICATE_PACKAGE,
                            "Core android package being redefined.  Skipping.");
                }
            }

            // A package name must be unique; don't allow duplicates
            if ((scanFlags & SCAN_NEW_INSTALL) == 0
                    && mPm.mPackages.containsKey(pkg.getPackageName())) {
                throw new PackageManagerException(INSTALL_FAILED_DUPLICATE_PACKAGE,
                        "Application package " + pkg.getPackageName()
                                + " already installed.  Skipping duplicate.");
            }

            if (pkg.isStaticSharedLibrary()) {
                // Static libs have a synthetic package name containing the version
                // but we still want the base name to be unique.
                if ((scanFlags & SCAN_NEW_INSTALL) == 0
                        && mPm.mPackages.containsKey(pkg.getManifestPackageName())) {
                    throw new PackageManagerException(
                            "Duplicate static shared lib provider package");
                }

                // Static shared libraries should have at least O target SDK
                if (pkg.getTargetSdkVersion() < Build.VERSION_CODES.O) {
                    throw new PackageManagerException(
                            "Packages declaring static-shared libs must target O SDK or higher");
                }

                // Package declaring static a shared lib cannot be instant apps
                if ((scanFlags & SCAN_AS_INSTANT_APP) != 0) {
                    throw new PackageManagerException(
                            "Packages declaring static-shared libs cannot be instant apps");
                }

                // Package declaring static a shared lib cannot be renamed since the package
                // name is synthetic and apps can't code around package manager internals.
                if (!ArrayUtils.isEmpty(pkg.getOriginalPackages())) {
                    throw new PackageManagerException(
                            "Packages declaring static-shared libs cannot be renamed");
                }

                // Package declaring static a shared lib cannot declare dynamic libs
                if (!ArrayUtils.isEmpty(pkg.getLibraryNames())) {
                    throw new PackageManagerException(
                            "Packages declaring static-shared libs cannot declare dynamic libs");
                }

                // Package declaring static a shared lib cannot declare shared users
                if (pkg.getSharedUserId() != null) {
                    throw new PackageManagerException(
                            "Packages declaring static-shared libs cannot declare shared users");
                }

                // Static shared libs cannot declare activities
                if (!pkg.getActivities().isEmpty()) {
                    throw new PackageManagerException(
                            "Static shared libs cannot declare activities");
                }

                // Static shared libs cannot declare services
                if (!pkg.getServices().isEmpty()) {
                    throw new PackageManagerException(
                            "Static shared libs cannot declare services");
                }

                // Static shared libs cannot declare providers
                if (!pkg.getProviders().isEmpty()) {
                    throw new PackageManagerException(
                            "Static shared libs cannot declare content providers");
                }

                // Static shared libs cannot declare receivers
                if (!pkg.getReceivers().isEmpty()) {
                    throw new PackageManagerException(
                            "Static shared libs cannot declare broadcast receivers");
                }

                // Static shared libs cannot declare permission groups
                if (!pkg.getPermissionGroups().isEmpty()) {
                    throw new PackageManagerException(
                            "Static shared libs cannot declare permission groups");
                }

                // Static shared libs cannot declare attributions
                if (!pkg.getAttributions().isEmpty()) {
                    throw new PackageManagerException(
                            "Static shared libs cannot declare features");
                }

                // Static shared libs cannot declare permissions
                if (!pkg.getPermissions().isEmpty()) {
                    throw new PackageManagerException(
                            "Static shared libs cannot declare permissions");
                }

                // Static shared libs cannot declare protected broadcasts
                if (!pkg.getProtectedBroadcasts().isEmpty()) {
                    throw new PackageManagerException(
                            "Static shared libs cannot declare protected broadcasts");
                }

                // Static shared libs cannot be overlay targets
                if (pkg.getOverlayTarget() != null) {
                    throw new PackageManagerException(
                            "Static shared libs cannot be overlay targets");
                }

                // The version codes must be ordered as lib versions
                long minVersionCode = Long.MIN_VALUE;
                long maxVersionCode = Long.MAX_VALUE;

                WatchedLongSparseArray<SharedLibraryInfo> versionedLib = mPm.mSharedLibraries.get(
                        pkg.getStaticSharedLibName());
                if (versionedLib != null) {
                    final int versionCount = versionedLib.size();
                    for (int i = 0; i < versionCount; i++) {
                        SharedLibraryInfo libInfo = versionedLib.valueAt(i);
                        final long libVersionCode = libInfo.getDeclaringPackage()
                                .getLongVersionCode();
                        if (libInfo.getLongVersion() < pkg.getStaticSharedLibVersion()) {
                            minVersionCode = Math.max(minVersionCode, libVersionCode + 1);
                        } else if (libInfo.getLongVersion()
                                > pkg.getStaticSharedLibVersion()) {
                            maxVersionCode = Math.min(maxVersionCode, libVersionCode - 1);
                        } else {
                            minVersionCode = maxVersionCode = libVersionCode;
                            break;
                        }
                    }
                }
                if (pkg.getLongVersionCode() < minVersionCode
                        || pkg.getLongVersionCode() > maxVersionCode) {
                    throw new PackageManagerException("Static shared"
                            + " lib version codes must be ordered as lib versions");
                }
            }

            // If we're only installing presumed-existing packages, require that the
            // scanned APK is both already known and at the path previously established
            // for it.  Previously unknown packages we pick up normally, but if we have an
            // a priori expectation about this package's install presence, enforce it.
            // With a singular exception for new system packages. When an OTA contains
            // a new system package, we allow the codepath to change from a system location
            // to the user-installed location. If we don't allow this change, any newer,
            // user-installed version of the application will be ignored.
            if ((scanFlags & SCAN_REQUIRE_KNOWN) != 0) {
                if (mPm.mExpectingBetter.containsKey(pkg.getPackageName())) {
                    Slog.w(TAG, "Relax SCAN_REQUIRE_KNOWN requirement for package "
                            + pkg.getPackageName());
                } else {
                    PackageSetting known = mPm.mSettings.getPackageLPr(pkg.getPackageName());
                    if (known != null) {
                        if (DEBUG_PACKAGE_SCANNING) {
                            Log.d(TAG, "Examining " + pkg.getPath()
                                    + " and requiring known path " + known.getPathString());
                        }
                        if (!pkg.getPath().equals(known.getPathString())) {
                            throw new PackageManagerException(INSTALL_FAILED_PACKAGE_CHANGED,
                                    "Application package " + pkg.getPackageName()
                                            + " found at " + pkg.getPath()
                                            + " but expected at " + known.getPathString()
                                            + "; ignoring.");
                        }
                    } else {
                        throw new PackageManagerException(INSTALL_FAILED_INVALID_INSTALL_LOCATION,
                                "Application package " + pkg.getPackageName()
                                        + " not found; ignoring.");
                    }
                }
            }

            // Verify that this new package doesn't have any content providers
            // that conflict with existing packages.  Only do this if the
            // package isn't already installed, since we don't want to break
            // things that are installed.
            if ((scanFlags & SCAN_NEW_INSTALL) != 0) {
                mPm.mComponentResolver.assertProvidersNotDefined(pkg);
            }

            // If this package has defined explicit processes, then ensure that these are
            // the only processes used by its components.
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

            // Verify that packages sharing a user with a privileged app are marked as privileged.
            if (!pkg.isPrivileged() && (pkg.getSharedUserId() != null)) {
                SharedUserSetting sharedUserSetting = null;
                try {
                    sharedUserSetting = mPm.mSettings.getSharedUserLPw(pkg.getSharedUserId(),
                            0, 0, false);
                } catch (PackageManagerException ignore) {
                }
                if (sharedUserSetting != null && sharedUserSetting.isPrivileged()) {
                    // Exempt SharedUsers signed with the platform key.
                    PackageSetting platformPkgSetting = mPm.mSettings.getPackageLPr("android");
                    if (!comparePackageSignatures(platformPkgSetting,
                            pkg.getSigningDetails().getSignatures())) {
                        throw new PackageManagerException("Apps that share a user with a "
                                + "privileged app must themselves be marked as privileged. "
                                + pkg.getPackageName() + " shares privileged user "
                                + pkg.getSharedUserId() + ".");
                    }
                }
            }

            // Apply policies specific for runtime resource overlays (RROs).
            if (pkg.getOverlayTarget() != null) {
                // System overlays have some restrictions on their use of the 'static' state.
                if ((scanFlags & SCAN_AS_SYSTEM) != 0) {
                    // We are scanning a system overlay. This can be the first scan of the
                    // system/vendor/oem partition, or an update to the system overlay.
                    if ((parseFlags & ParsingPackageUtils.PARSE_IS_SYSTEM_DIR) == 0) {
                        // This must be an update to a system overlay. Immutable overlays cannot be
                        // upgraded.
                        Objects.requireNonNull(mPm.mOverlayConfig,
                                "Parsing non-system dir before overlay configs are initialized");
                        if (!mPm.mOverlayConfig.isMutable(pkg.getPackageName())) {
                            throw new PackageManagerException("Overlay "
                                    + pkg.getPackageName()
                                    + " is static and cannot be upgraded.");
                        }
                    } else {
                        if ((scanFlags & SCAN_AS_VENDOR) != 0) {
                            if (pkg.getTargetSdkVersion() < getVendorPartitionVersion()) {
                                Slog.w(TAG, "System overlay " + pkg.getPackageName()
                                        + " targets an SDK below the required SDK level of vendor"
                                        + " overlays (" + getVendorPartitionVersion() + ")."
                                        + " This will become an install error in a future release");
                            }
                        } else if (pkg.getTargetSdkVersion() < Build.VERSION.SDK_INT) {
                            Slog.w(TAG, "System overlay " + pkg.getPackageName()
                                    + " targets an SDK below the required SDK level of system"
                                    + " overlays (" + Build.VERSION.SDK_INT + ")."
                                    + " This will become an install error in a future release");
                        }
                    }
                } else {
                    // A non-preloaded overlay packages must have targetSdkVersion >= Q, or be
                    // signed with the platform certificate. Check this in increasing order of
                    // computational cost.
                    if (pkg.getTargetSdkVersion() < Build.VERSION_CODES.Q) {
                        final PackageSetting platformPkgSetting =
                                mPm.mSettings.getPackageLPr("android");
                        if (!comparePackageSignatures(platformPkgSetting,
                                pkg.getSigningDetails().getSignatures())) {
                            throw new PackageManagerException("Overlay "
                                    + pkg.getPackageName()
                                    + " must target Q or later, "
                                    + "or be signed with the platform certificate");
                        }
                    }

                    // A non-preloaded overlay package, without <overlay android:targetName>, will
                    // only be used if it is signed with the same certificate as its target OR if
                    // it is signed with the same certificate as a reference package declared
                    // in 'overlay-config-signature' tag of SystemConfig.
                    // If the target is already installed or 'overlay-config-signature' tag in
                    // SystemConfig is set, check this here to augment the last line of defense
                    // which is OMS.
                    if (pkg.getOverlayTargetOverlayableName() == null) {
                        final PackageSetting targetPkgSetting =
                                mPm.mSettings.getPackageLPr(pkg.getOverlayTarget());
                        if (targetPkgSetting != null) {
                            if (!comparePackageSignatures(targetPkgSetting,
                                    pkg.getSigningDetails().getSignatures())) {
                                // check reference signature
                                if (mPm.mOverlayConfigSignaturePackage == null) {
                                    throw new PackageManagerException("Overlay "
                                            + pkg.getPackageName() + " and target "
                                            + pkg.getOverlayTarget() + " signed with"
                                            + " different certificates, and the overlay lacks"
                                            + " <overlay android:targetName>");
                                }
                                final PackageSetting refPkgSetting =
                                        mPm.mSettings.getPackageLPr(
                                                mPm.mOverlayConfigSignaturePackage);
                                if (!comparePackageSignatures(refPkgSetting,
                                        pkg.getSigningDetails().getSignatures())) {
                                    throw new PackageManagerException("Overlay "
                                            + pkg.getPackageName() + " signed with a different "
                                            + "certificate than both the reference package and "
                                            + "target " + pkg.getOverlayTarget() + ", and the "
                                            + "overlay lacks <overlay android:targetName>");
                                }
                            }
                        }
                    }
                }
            }

            // If the package is not on a system partition ensure it is signed with at least the
            // minimum signature scheme version required for its target SDK.
            if ((parseFlags & ParsingPackageUtils.PARSE_IS_SYSTEM_DIR) == 0) {
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

    /**
     * Applies the adjusted ABI calculated by
     * {@link PackageAbiHelper#getAdjustedAbiForSharedUser(Set, AndroidPackage)} to all
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
        for (PackageSetting ps : sharedUserSetting.packages) {
            if (scannedPackage == null || !scannedPackage.getPackageName().equals(ps.name)) {
                if (ps.primaryCpuAbiString != null) {
                    continue;
                }

                ps.primaryCpuAbiString = adjustedAbi;
                if (ps.pkg != null) {
                    if (!TextUtils.equals(adjustedAbi,
                            AndroidPackageUtils.getRawPrimaryCpuAbi(ps.pkg))) {
                        if (DEBUG_ABI_SELECTION) {
                            Slog.i(TAG,
                                    "Adjusting ABI for " + ps.name + " to " + adjustedAbi
                                            + " (scannedPackage="
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

    /**
     * Applies policy to the parsed package based upon the given policy flags.
     * Ensures the package is in a good state.
     * <p>
     * Implementation detail: This method must NOT have any side effect. It would
     * ideally be static, but, it requires locks to read system state.
     */
    private static void applyPolicy(ParsedPackage parsedPackage,
            final @PackageManagerService.ScanFlags int scanFlags, AndroidPackage platformPkg,
            boolean isUpdatedSystemApp) {
        if ((scanFlags & SCAN_AS_SYSTEM) != 0) {
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
                        platformPkg.getSigningDetails().getSignatures(),
                        parsedPackage.getSigningDetails().getSignatures()
                ) == PackageManager.SIGNATURE_MATCH))
        );

        if (!parsedPackage.isSystem()) {
            // Only system apps can use these features.
            parsedPackage.clearOriginalPackages()
                    .clearAdoptPermissions();
        }

        PackageBackwardCompatibility.modifySharedLibraries(parsedPackage, isUpdatedSystemApp);
    }

    /**
     * Enforces code policy for the package. This ensures that if an APK has
     * declared hasCode="true" in its manifest that the APK actually contains
     * code.
     *
     * @throws PackageManagerException If bytecode could not be found when it should exist
     */
    private static void assertCodePolicy(AndroidPackage pkg)
            throws PackageManagerException {
        final boolean shouldHaveCode = pkg.isHasCode();
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

    /**
     * Returns the "real" name of the package.
     * <p>This may differ from the package's actual name if the application has already
     * been installed under one of this package's original names.
     */
    private static @Nullable String getRealPackageName(@NonNull AndroidPackage pkg,
            @Nullable String renamedPkgName) {
        if (isPackageRenamed(pkg, renamedPkgName)) {
            return AndroidPackageUtils.getRealPackageOrNull(pkg);
        }
        return null;
    }

    /** Returns {@code true} if the package has been renamed. Otherwise, {@code false}. */
    private static boolean isPackageRenamed(@NonNull AndroidPackage pkg,
            @Nullable String renamedPkgName) {
        return pkg.getOriginalPackages().contains(renamedPkgName);
    }

    /**
     * Renames the package if it was installed under a different name.
     * <p>When we've already installed the package under an original name, update
     * the new package so we can continue to have the old name.
     */
    private static void ensurePackageRenamed(@NonNull ParsedPackage parsedPackage,
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
    private static boolean apkHasCode(String fileName) {
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
    private static void configurePackageComponents(AndroidPackage pkg) {
        final ArrayMap<String, Boolean> componentsEnabledStates = SystemConfig.getInstance()
                .getComponentsEnabledStates(pkg.getPackageName());
        if (componentsEnabledStates == null) {
            return;
        }

        for (int i = ArrayUtils.size(pkg.getActivities()) - 1; i >= 0; i--) {
            final ParsedActivity component = pkg.getActivities().get(i);
            final Boolean enabled = componentsEnabledStates.get(component.getName());
            if (enabled != null) {
                component.setEnabled(enabled);
            }
        }

        for (int i = ArrayUtils.size(pkg.getReceivers()) - 1; i >= 0; i--) {
            final ParsedActivity component = pkg.getReceivers().get(i);
            final Boolean enabled = componentsEnabledStates.get(component.getName());
            if (enabled != null) {
                component.setEnabled(enabled);
            }
        }

        for (int i = ArrayUtils.size(pkg.getProviders()) - 1; i >= 0; i--) {
            final ParsedProvider component = pkg.getProviders().get(i);
            final Boolean enabled = componentsEnabledStates.get(component.getName());
            if (enabled != null) {
                component.setEnabled(enabled);
            }
        }

        for (int i = ArrayUtils.size(pkg.getServices()) - 1; i >= 0; i--) {
            final ParsedService component = pkg.getServices().get(i);
            final Boolean enabled = componentsEnabledStates.get(component.getName());
            if (enabled != null) {
                component.setEnabled(enabled);
            }
        }
    }

    private static int getVendorPartitionVersion() {
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
}
