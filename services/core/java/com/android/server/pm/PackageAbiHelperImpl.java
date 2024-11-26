/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.content.pm.PackageManager.INSTALL_FAILED_CPU_ABI_INCOMPATIBLE;
import static android.content.pm.PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
import static android.content.pm.parsing.ApkLiteParseUtils.isApkFile;
import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;
import static android.os.incremental.IncrementalManager.isIncrementalPath;

import static com.android.internal.content.NativeLibraryHelper.LIB64_DIR_NAME;
import static com.android.internal.content.NativeLibraryHelper.LIB_DIR_NAME;
import static com.android.server.pm.InstructionSets.getPreferredInstructionSet;
import static com.android.server.pm.InstructionSets.getPrimaryInstructionSet;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.Flags;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.FileUtils;
import android.os.SystemProperties;
import android.os.Trace;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.content.NativeLibraryHelper;
import com.android.internal.util.ArrayUtils;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;

import dalvik.system.VMRuntime;

import libcore.io.IoUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class PackageAbiHelperImpl implements PackageAbiHelper {

    @Nullable
    private static String[] sNativelySupported32BitAbis = null;
    @Nullable
    private static String[] sNativelySupported64BitAbis = null;

    private static String calculateBundledApkRoot(final String codePathString) {
        final File codePath = new File(codePathString);
        final File codeRoot;
        if (FileUtils.contains(Environment.getRootDirectory(), codePath)) {
            codeRoot = Environment.getRootDirectory();
        } else if (FileUtils.contains(Environment.getOemDirectory(), codePath)) {
            codeRoot = Environment.getOemDirectory();
        } else if (FileUtils.contains(Environment.getVendorDirectory(), codePath)) {
            codeRoot = Environment.getVendorDirectory();
        } else if (FileUtils.contains(Environment.getOdmDirectory(), codePath)) {
            codeRoot = Environment.getOdmDirectory();
        } else if (FileUtils.contains(Environment.getProductDirectory(), codePath)) {
            codeRoot = Environment.getProductDirectory();
        } else if (FileUtils.contains(Environment.getSystemExtDirectory(), codePath)) {
            codeRoot = Environment.getSystemExtDirectory();
        } else if (FileUtils.contains(Environment.getOdmDirectory(), codePath)) {
            codeRoot = Environment.getOdmDirectory();
        } else if (FileUtils.contains(Environment.getApexDirectory(), codePath)) {
            String fullPath = codePath.getAbsolutePath();
            String[] parts = fullPath.split(File.separator);
            if (parts.length > 2) {
                codeRoot = new File(parts[1] + File.separator + parts[2]);
            } else {
                Slog.w(PackageManagerService.TAG, "Can't canonicalize code path " + codePath);
                codeRoot = Environment.getApexDirectory();
            }
        } else {
            // Unrecognized code path; take its top real segment as the apk root:
            // e.g. /something/app/blah.apk => /something
            try {
                File f = codePath.getCanonicalFile();
                File parent = f.getParentFile();    // non-null because codePath is a file
                File tmp;
                while ((tmp = parent.getParentFile()) != null) {
                    f = parent;
                    parent = tmp;
                }
                codeRoot = f;
                Slog.w(PackageManagerService.TAG, "Unrecognized code path "
                        + codePath + " - using " + codeRoot);
            } catch (IOException e) {
                // Can't canonicalize the code path -- shenanigans?
                Slog.w(PackageManagerService.TAG, "Can't canonicalize code path " + codePath);
                return Environment.getRootDirectory().getPath();
            }
        }
        return codeRoot.getPath();
    }

    // Utility method that returns the relative package path with respect
    // to the installation directory. Like say for /data/data/com.test-1.apk
    // string com.test-1 is returned.
    private static String deriveCodePathName(String codePath) {
        if (codePath == null) {
            return null;
        }
        final File codeFile = new File(codePath);
        final String name = codeFile.getName();
        if (codeFile.isDirectory()) {
            return name;
        } else if (name.endsWith(".apk") || name.endsWith(".tmp")) {
            final int lastDot = name.lastIndexOf('.');
            return name.substring(0, lastDot);
        } else {
            Slog.w(PackageManagerService.TAG, "Odd, " + codePath + " doesn't look like an APK");
            return null;
        }
    }

    private static void maybeThrowExceptionForMultiArchCopy(String message, int copyRet,
            boolean forceMatch) throws PackageManagerException {
        if (copyRet < 0) {
            if (copyRet != PackageManager.NO_NATIVE_LIBRARIES
                    && copyRet != PackageManager.INSTALL_FAILED_NO_MATCHING_ABIS) {
                throw new PackageManagerException(copyRet, message);
            }

            if (forceMatch && copyRet == PackageManager.INSTALL_FAILED_NO_MATCHING_ABIS) {
                throw new PackageManagerException(
                        PackageManager.INSTALL_FAILED_MULTI_ARCH_NOT_MATCH_ALL_NATIVE_ABIS,
                        "The multiArch app's native libs don't support all the natively"
                                + " supported ABIs of the device.");
            }
        }
    }

    @Override
    public NativeLibraryPaths deriveNativeLibraryPaths(AndroidPackage pkg, boolean isSystemApp,
            boolean isUpdatedSystemApp, File appLib32InstallDir) {
        // Trying to derive the paths, thus need the raw ABI info from the parsed package, and the
        // current state in PackageSetting is irrelevant.
        return deriveNativeLibraryPaths(new Abis(AndroidPackageUtils.getRawPrimaryCpuAbi(pkg),
                AndroidPackageUtils.getRawSecondaryCpuAbi(pkg)), appLib32InstallDir, pkg.getPath(),
                pkg.getBaseApkPath(), isSystemApp, isUpdatedSystemApp);
    }

    private static NativeLibraryPaths deriveNativeLibraryPaths(final Abis abis,
            final File appLib32InstallDir, final String codePath, final String sourceDir,
            final boolean isSystemApp, final boolean isUpdatedSystemApp) {
        final File codeFile = new File(codePath);
        final boolean bundledApp = isSystemApp && !isUpdatedSystemApp;

        final String nativeLibraryRootDir;
        final boolean nativeLibraryRootRequiresIsa;
        final String nativeLibraryDir;
        final String secondaryNativeLibraryDir;

        if (isApkFile(codeFile)) {
            // Monolithic install
            if (bundledApp) {
                // If "/system/lib64/apkname" exists, assume that is the per-package
                // native library directory to use; otherwise use "/system/lib/apkname".
                final String apkRoot = calculateBundledApkRoot(sourceDir);
                final boolean is64Bit = VMRuntime.is64BitInstructionSet(
                        getPrimaryInstructionSet(abis));

                // This is a bundled system app so choose the path based on the ABI.
                // if it's a 64 bit abi, use lib64 otherwise use lib32. Note that this
                // is just the default path.
                final String apkName = deriveCodePathName(codePath);
                final String libDir = is64Bit ? LIB64_DIR_NAME : LIB_DIR_NAME;
                nativeLibraryRootDir = Environment.buildPath(new File(apkRoot), libDir,
                        apkName).getAbsolutePath();

                if (abis.secondary != null) {
                    final String secondaryLibDir = is64Bit ? LIB_DIR_NAME : LIB64_DIR_NAME;
                    secondaryNativeLibraryDir = Environment.buildPath(new File(apkRoot),
                            secondaryLibDir, apkName).getAbsolutePath();
                } else {
                    secondaryNativeLibraryDir = null;
                }
            } else {
                final String apkName = deriveCodePathName(codePath);
                nativeLibraryRootDir = new File(appLib32InstallDir, apkName)
                        .getAbsolutePath();
                secondaryNativeLibraryDir = null;
            }

            nativeLibraryRootRequiresIsa = false;
            nativeLibraryDir = nativeLibraryRootDir;
        } else {
            // Cluster install
            nativeLibraryRootDir = new File(codeFile, LIB_DIR_NAME).getAbsolutePath();
            nativeLibraryRootRequiresIsa = true;

            nativeLibraryDir = new File(nativeLibraryRootDir,
                    getPrimaryInstructionSet(abis)).getAbsolutePath();

            if (abis.secondary != null) {
                secondaryNativeLibraryDir = new File(nativeLibraryRootDir,
                        VMRuntime.getInstructionSet(abis.secondary)).getAbsolutePath();
            } else {
                secondaryNativeLibraryDir = null;
            }
        }
        return new NativeLibraryPaths(nativeLibraryRootDir, nativeLibraryRootRequiresIsa,
                nativeLibraryDir, secondaryNativeLibraryDir);
    }

    @Override
    public Abis getBundledAppAbis(AndroidPackage pkg) {
        final String apkName = deriveCodePathName(pkg.getPath());

        // If "/system/lib64/apkname" exists, assume that is the per-package
        // native library directory to use; otherwise use "/system/lib/apkname".
        final String apkRoot = calculateBundledApkRoot(pkg.getBaseApkPath());
        final Abis abis = getBundledAppAbi(pkg, apkRoot, apkName);
        return abis;
    }

    /**
     * Deduces the ABI of a bundled app and sets the relevant fields on the
     * parsed pkg object.
     *
     * @param apkRoot the root of the installed apk, something like {@code /system} or
     *                {@code /oem} under which system libraries are installed.
     * @param apkName the name of the installed package.
     */
    private Abis getBundledAppAbi(AndroidPackage pkg, String apkRoot, String apkName) {
        final File codeFile = new File(pkg.getPath());

        final boolean has64BitLibs;
        final boolean has32BitLibs;

        final String primaryCpuAbi;
        final String secondaryCpuAbi;
        if (isApkFile(codeFile)) {
            // Monolithic install
            has64BitLibs =
                    (new File(apkRoot, new File(LIB64_DIR_NAME, apkName).getPath())).exists();
            has32BitLibs = (new File(apkRoot, new File(LIB_DIR_NAME, apkName).getPath())).exists();
        } else {
            // Cluster install
            final File rootDir = new File(codeFile, LIB_DIR_NAME);
            if (!ArrayUtils.isEmpty(Build.SUPPORTED_64_BIT_ABIS)
                    && !TextUtils.isEmpty(Build.SUPPORTED_64_BIT_ABIS[0])) {
                final String isa = VMRuntime.getInstructionSet(Build.SUPPORTED_64_BIT_ABIS[0]);
                has64BitLibs = (new File(rootDir, isa)).exists();
            } else {
                has64BitLibs = false;
            }
            if (!ArrayUtils.isEmpty(Build.SUPPORTED_32_BIT_ABIS)
                    && !TextUtils.isEmpty(Build.SUPPORTED_32_BIT_ABIS[0])) {
                final String isa = VMRuntime.getInstructionSet(Build.SUPPORTED_32_BIT_ABIS[0]);
                has32BitLibs = (new File(rootDir, isa)).exists();
            } else {
                has32BitLibs = false;
            }
        }

        if (has64BitLibs && !has32BitLibs) {
            // The package has 64 bit libs, but not 32 bit libs. Its primary
            // ABI should be 64 bit. We can safely assume here that the bundled
            // native libraries correspond to the most preferred ABI in the list.

            primaryCpuAbi = Build.SUPPORTED_64_BIT_ABIS[0];
            secondaryCpuAbi = null;
        } else if (has32BitLibs && !has64BitLibs) {
            // The package has 32 bit libs but not 64 bit libs. Its primary
            // ABI should be 32 bit.

            primaryCpuAbi = Build.SUPPORTED_32_BIT_ABIS[0];
            secondaryCpuAbi = null;
        } else if (has32BitLibs && has64BitLibs) {
            // The application has both 64 and 32 bit bundled libraries. We check
            // here that the app declares multiArch support, and warn if it doesn't.
            //
            // We will be lenient here and record both ABIs. The primary will be the
            // ABI that's higher on the list, i.e, a device that's configured to prefer
            // 64 bit apps will see a 64 bit primary ABI,

            if (!pkg.isMultiArch()) {
                Slog.e(PackageManagerService.TAG,
                        "Package " + pkg + " has multiple bundled libs, but is not multiarch.");
            }

            if (VMRuntime.is64BitInstructionSet(getPreferredInstructionSet())) {
                primaryCpuAbi = Build.SUPPORTED_64_BIT_ABIS[0];
                secondaryCpuAbi = Build.SUPPORTED_32_BIT_ABIS[0];
            } else {
                primaryCpuAbi = Build.SUPPORTED_32_BIT_ABIS[0];
                secondaryCpuAbi = Build.SUPPORTED_64_BIT_ABIS[0];
            }
        } else {
            primaryCpuAbi = null;
            secondaryCpuAbi = null;
        }
        return new Abis(primaryCpuAbi, secondaryCpuAbi);
    }

    @NonNull
    private static String[] getNativelySupportedAbis(@NonNull String[] supportedAbis) {
        List<String> nativelySupportedAbis = new ArrayList<>();
        for (int i = 0; i < supportedAbis.length; i++) {
            final String currentAbi = supportedAbis[i];
            // In presence of a native bridge this means the Abi is emulated.
            final String currentIsa = VMRuntime.getInstructionSet(currentAbi);
            if (TextUtils.isEmpty(SystemProperties.get("ro.dalvik.vm.isa." + currentIsa))) {
                nativelySupportedAbis.add(currentAbi);
            }
        }
        return nativelySupportedAbis.toArray(new String[0]);
    }

    private static String[] getNativelySupported32BitAbis() {
        if (sNativelySupported32BitAbis != null) {
            return sNativelySupported32BitAbis;
        }

        sNativelySupported32BitAbis = getNativelySupportedAbis(Build.SUPPORTED_32_BIT_ABIS);
        return sNativelySupported32BitAbis;
    }

    private static String[] getNativelySupported64BitAbis() {
        if (sNativelySupported64BitAbis != null) {
            return sNativelySupported64BitAbis;
        }

        sNativelySupported64BitAbis = getNativelySupportedAbis(Build.SUPPORTED_64_BIT_ABIS);
        return sNativelySupported64BitAbis;
    }

    @Override
    @SuppressWarnings("AndroidFrameworkCompatChange") // the check is before the apk is installed
    public Pair<Abis, NativeLibraryPaths> derivePackageAbi(AndroidPackage pkg, boolean isSystemApp,
            boolean isUpdatedSystemApp, String cpuAbiOverride, File appLib32InstallDir)
            throws PackageManagerException {
        // Give ourselves some initial paths; we'll come back for another
        // pass once we've determined ABI below.
        String pkgRawPrimaryCpuAbi = AndroidPackageUtils.getRawPrimaryCpuAbi(pkg);
        String pkgRawSecondaryCpuAbi = AndroidPackageUtils.getRawSecondaryCpuAbi(pkg);
        final NativeLibraryPaths initialLibraryPaths = deriveNativeLibraryPaths(
                new Abis(pkgRawPrimaryCpuAbi, pkgRawSecondaryCpuAbi),
                appLib32InstallDir, pkg.getPath(),
                pkg.getBaseApkPath(), isSystemApp,
                isUpdatedSystemApp);

        final boolean extractLibs = shouldExtractLibs(pkg, isSystemApp, isUpdatedSystemApp);

        final String nativeLibraryRootStr = initialLibraryPaths.nativeLibraryRootDir;
        final boolean useIsaSpecificSubdirs = initialLibraryPaths.nativeLibraryRootRequiresIsa;
        final boolean onIncremental = isIncrementalPath(pkg.getPath());

        String primaryCpuAbi = null;
        String secondaryCpuAbi = null;

        NativeLibraryHelper.Handle handle = null;
        try {
            handle = AndroidPackageUtils.createNativeLibraryHandle(pkg);
            // TODO(multiArch): This can be null for apps that didn't go through the
            // usual installation process. We can calculate it again, like we
            // do during install time.
            //
            // TODO(multiArch): Why do we need to rescan ASEC apps again ? It seems totally
            // unnecessary.
            final File nativeLibraryRoot = new File(nativeLibraryRootStr);

            // Null out the abis so that they can be recalculated.
            primaryCpuAbi = null;
            secondaryCpuAbi = null;
            if (pkg.isMultiArch()) {
                // Force the match for these cases
                // 1. pkg.getTargetSdkVersion >= Build.VERSION_CODES.VANILLA_ICE_CREAM
                // 2. cpuAbiOverride is null. If it is non-null, it is set via shell for testing
                final boolean forceMatch = Flags.forceMultiArchNativeLibsMatch()
                        && pkg.getTargetSdkVersion() >= Build.VERSION_CODES.VANILLA_ICE_CREAM
                        && cpuAbiOverride == null;

                String[] supported32BitAbis = forceMatch ? getNativelySupported32BitAbis()
                        : Build.SUPPORTED_32_BIT_ABIS;
                String[] supported64BitAbis = forceMatch ? getNativelySupported64BitAbis()
                        : Build.SUPPORTED_64_BIT_ABIS;

                final boolean systemSupports32BitAbi = supported32BitAbis.length > 0;
                final boolean systemSupports64BitAbi = supported64BitAbis.length > 0;

                int abi32 = PackageManager.NO_NATIVE_LIBRARIES;
                int abi64 = PackageManager.NO_NATIVE_LIBRARIES;
                if (systemSupports32BitAbi) {
                    if (extractLibs) {
                        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "copyNativeBinaries");
                        abi32 = NativeLibraryHelper.copyNativeBinariesForSupportedAbi(handle,
                                nativeLibraryRoot, supported32BitAbis,
                                useIsaSpecificSubdirs, onIncremental);
                    } else {
                        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "findSupportedAbi");
                        abi32 = NativeLibraryHelper.findSupportedAbi(
                                handle, supported32BitAbis);
                    }
                    Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                }

                // Shared library native code should be in the APK zip aligned
                if (abi32 >= 0 && AndroidPackageUtils.isLibrary(pkg) && extractLibs) {
                    throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR,
                            "Shared library native lib extraction not supported");
                }

                maybeThrowExceptionForMultiArchCopy(
                        "Error unpackaging 32 bit native libs for multiarch app.", abi32,
                        forceMatch && systemSupports32BitAbi);

                if (systemSupports64BitAbi) {
                    if (extractLibs) {
                        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "copyNativeBinaries");
                        abi64 = NativeLibraryHelper.copyNativeBinariesForSupportedAbi(handle,
                                nativeLibraryRoot, supported64BitAbis,
                                useIsaSpecificSubdirs, onIncremental);
                    } else {
                        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "findSupportedAbi");
                        abi64 = NativeLibraryHelper.findSupportedAbi(
                                handle, supported64BitAbis);
                    }
                    Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                }

                maybeThrowExceptionForMultiArchCopy(
                        "Error unpackaging 64 bit native libs for multiarch app.", abi64,
                        forceMatch && systemSupports64BitAbi);

                if (abi64 >= 0) {
                    // Shared library native libs should be in the APK zip aligned
                    if (extractLibs && AndroidPackageUtils.isLibrary(pkg)) {
                        throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR,
                                "Shared library native lib extraction not supported");
                    }
                    primaryCpuAbi = supported64BitAbis[abi64];
                }

                if (abi32 >= 0) {
                    final String abi = supported32BitAbis[abi32];
                    if (abi64 >= 0) {
                        if (pkg.is32BitAbiPreferred()) {
                            secondaryCpuAbi = primaryCpuAbi;
                            primaryCpuAbi = abi;
                        } else {
                            secondaryCpuAbi = abi;
                        }
                    } else {
                        primaryCpuAbi = abi;
                    }
                }
            } else {
                String[] abiList = (cpuAbiOverride != null)
                        ? new String[]{cpuAbiOverride} : Build.SUPPORTED_ABIS;

                // If an app that contains RenderScript has target API level < 21, it needs to run
                // with 32-bit ABI, and its APK file will contain a ".bc" file.
                // If an app that contains RenderScript has target API level >= 21, it can run with
                // either 32-bit or 64-bit ABI, and its APK file will not contain a ".bc" file.
                // Therefore, on a device that supports both 32-bit and 64-bit ABIs, we scan the app
                // APK to see if it has a ".bc" file. If so, we will run it with 32-bit ABI.
                // However, if the device only supports 64-bit ABI but does not support 32-bit ABI,
                // we will fail the installation for such an app because it won't be able to run.
                boolean needsRenderScriptOverride = false;
                // No need to check if the device only supports 32-bit
                if (Build.SUPPORTED_64_BIT_ABIS.length > 0 && cpuAbiOverride == null
                        && NativeLibraryHelper.hasRenderscriptBitcode(handle)) {
                    if (Build.SUPPORTED_32_BIT_ABIS.length > 0) {
                        abiList = Build.SUPPORTED_32_BIT_ABIS;
                        needsRenderScriptOverride = true;
                    } else {
                        throw new PackageManagerException(
                                INSTALL_FAILED_CPU_ABI_INCOMPATIBLE,
                                "Apps that contain RenderScript with target API level < 21 are not "
                                        + "supported on 64-bit only platforms");
                    }
                }

                final int copyRet;
                if (extractLibs) {
                    Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "copyNativeBinaries");
                    copyRet = NativeLibraryHelper.copyNativeBinariesForSupportedAbi(handle,
                            nativeLibraryRoot, abiList, useIsaSpecificSubdirs, onIncremental);
                } else {
                    Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "findSupportedAbi");
                    copyRet = NativeLibraryHelper.findSupportedAbi(handle, abiList);
                }
                Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);

                if (copyRet < 0 && copyRet != PackageManager.NO_NATIVE_LIBRARIES) {
                    throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR,
                            "Error unpackaging native libs for app, errorCode=" + copyRet);
                }

                if (copyRet >= 0) {
                    // Shared libraries that have native libs must be multi-architecture
                    if (AndroidPackageUtils.isLibrary(pkg)) {
                        throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR,
                                "Shared library with native libs must be multiarch");
                    }
                    primaryCpuAbi = abiList[copyRet];
                } else if (copyRet == PackageManager.NO_NATIVE_LIBRARIES
                        && cpuAbiOverride != null) {
                    primaryCpuAbi = cpuAbiOverride;
                } else if (needsRenderScriptOverride) {
                    primaryCpuAbi = abiList[0];
                }
            }
        } catch (IOException ioe) {
            Slog.e(PackageManagerService.TAG, "Unable to get canonical file " + ioe.toString());
        } finally {
            IoUtils.closeQuietly(handle);
        }

        // Now that we've calculated the ABIs and determined if it's an internal app,
        // we will go ahead and populate the nativeLibraryPath.

        final Abis abis = new Abis(primaryCpuAbi, secondaryCpuAbi);
        return new Pair<>(abis,
                deriveNativeLibraryPaths(abis, appLib32InstallDir,
                        pkg.getPath(), pkg.getBaseApkPath(), isSystemApp,
                        isUpdatedSystemApp));
    }

    private boolean shouldExtractLibs(AndroidPackage pkg, boolean isSystemApp,
            boolean isUpdatedSystemApp) {
        // We shouldn't extract libs if the package is a library or if extractNativeLibs=false
        boolean extractLibs = !AndroidPackageUtils.isLibrary(pkg)
                && pkg.isExtractNativeLibrariesRequested();
        // We shouldn't attempt to extract libs from system app when it was not updated.
        if (isSystemApp && !isUpdatedSystemApp) {
            extractLibs = false;
        }
        return extractLibs;
    }

    /**
     * Adjusts ABIs for a set of packages belonging to a shared user so that they all match.
     * i.e, so that all packages can be run inside a single process if required.
     *
     * Optionally, callers can pass in a parsed package via {@code newPackage} in which case
     * this function will either try and make the ABI for all packages in
     * {@code packagesForUser} match {@code scannedPackage} or will update the ABI of
     * {@code scannedPackage} to match the ABI selected for {@code packagesForUser}. This
     * variant is used when installing or updating a package that belongs to a shared user.
     *
     * NOTE: We currently only match for the primary CPU abi string. Matching the secondary
     * adds unnecessary complexity.
     */
    @Override
    @Nullable
    public String getAdjustedAbiForSharedUser(
            ArraySet<? extends PackageStateInternal> packagesForUser,
            AndroidPackage scannedPackage) {
        String requiredInstructionSet = null;
        if (scannedPackage != null) {
            String pkgRawPrimaryCpuAbi = AndroidPackageUtils.getRawPrimaryCpuAbi(scannedPackage);
            if (pkgRawPrimaryCpuAbi != null) {
                requiredInstructionSet = VMRuntime.getInstructionSet(pkgRawPrimaryCpuAbi);
            }
        }

        PackageStateInternal requirer = null;
        for (PackageStateInternal ps : packagesForUser) {
            // If packagesForUser contains scannedPackage, we skip it. This will happen
            // when scannedPackage is an update of an existing package. Without this check,
            // we will never be able to change the ABI of any package belonging to a shared
            // user, even if it's compatible with other packages.
            if (scannedPackage != null && scannedPackage.getPackageName().equals(
                    ps.getPackageName())) {
                continue;
            }
            if (ps.getPrimaryCpuAbiLegacy() == null) {
                continue;
            }

            final String instructionSet =
                    VMRuntime.getInstructionSet(ps.getPrimaryCpuAbiLegacy());
            if (requiredInstructionSet != null && !requiredInstructionSet.equals(instructionSet)) {
                // We have a mismatch between instruction sets (say arm vs arm64) warn about
                // this but there's not much we can do.
                String errorMessage = "Instruction set mismatch, "
                        + ((requirer == null) ? "[caller]" : requirer)
                        + " requires " + requiredInstructionSet + " whereas " + ps
                        + " requires " + instructionSet;
                Slog.w(PackageManagerService.TAG, errorMessage);
            }

            if (requiredInstructionSet == null) {
                requiredInstructionSet = instructionSet;
                requirer = ps;
            }
        }

        if (requiredInstructionSet == null) {
            return null;
        }
        final String adjustedAbi;
        if (requirer != null) {
            // requirer != null implies that either scannedPackage was null or that
            // scannedPackage did not require an ABI, in which case we have to adjust
            // scannedPackage to match the ABI of the set (which is the same as
            // requirer's ABI)
            adjustedAbi = requirer.getPrimaryCpuAbiLegacy();
        } else {
            // requirer == null implies that we're updating all ABIs in the set to
            // match scannedPackage.
            adjustedAbi = AndroidPackageUtils.getRawPrimaryCpuAbi(scannedPackage);
        }
        return adjustedAbi;
    }
}
