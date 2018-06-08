/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.content.pm.PackageManager.INSTALL_FAILED_SHARED_USER_INCOMPATIBLE;
import static android.content.pm.PackageManager.INSTALL_FAILED_UPDATE_INCOMPATIBLE;
import static com.android.server.pm.PackageManagerService.COMPRESSED_EXTENSION;
import static com.android.server.pm.PackageManagerService.DEBUG_COMPRESSION;
import static com.android.server.pm.PackageManagerService.DEBUG_DEXOPT;
import static com.android.server.pm.PackageManagerService.STUB_SUFFIX;
import static com.android.server.pm.PackageManagerService.TAG;
import static com.android.server.pm.PackageManagerServiceUtils.logCriticalInfo;

import com.android.internal.content.NativeLibraryHelper;
import com.android.internal.util.FastPrintWriter;
import com.android.server.EventLogTags;
import com.android.server.pm.dex.DexManager;
import com.android.server.pm.dex.PackageDexUsage;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppGlobals;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PackageParser.PackageParserException;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Debug;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.service.pm.PackageServiceDumpProto;
import android.system.ErrnoException;
import android.system.Os;
import android.util.ArraySet;
import android.util.Log;
import android.util.PackageUtils;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import dalvik.system.VMRuntime;

import libcore.io.IoUtils;
import libcore.io.Libcore;
import libcore.io.Streams;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;

/**
 * Class containing helper methods for the PackageManagerService.
 *
 * {@hide}
 */
public class PackageManagerServiceUtils {
    private final static long SEVEN_DAYS_IN_MILLISECONDS = 7 * 24 * 60 * 60 * 1000;

    private static ArraySet<String> getPackageNamesForIntent(Intent intent, int userId) {
        List<ResolveInfo> ris = null;
        try {
            ris = AppGlobals.getPackageManager().queryIntentReceivers(intent, null, 0, userId)
                    .getList();
        } catch (RemoteException e) {
        }
        ArraySet<String> pkgNames = new ArraySet<String>();
        if (ris != null) {
            for (ResolveInfo ri : ris) {
                pkgNames.add(ri.activityInfo.packageName);
            }
        }
        return pkgNames;
    }

    // Sort a list of apps by their last usage, most recently used apps first. The order of
    // packages without usage data is undefined (but they will be sorted after the packages
    // that do have usage data).
    public static void sortPackagesByUsageDate(List<PackageParser.Package> pkgs,
            PackageManagerService packageManagerService) {
        if (!packageManagerService.isHistoricalPackageUsageAvailable()) {
            return;
        }

        Collections.sort(pkgs, (pkg1, pkg2) ->
                Long.compare(pkg2.getLatestForegroundPackageUseTimeInMills(),
                        pkg1.getLatestForegroundPackageUseTimeInMills()));
    }

    // Apply the given {@code filter} to all packages in {@code packages}. If tested positive, the
    // package will be removed from {@code packages} and added to {@code result} with its
    // dependencies. If usage data is available, the positive packages will be sorted by usage
    // data (with {@code sortTemp} as temporary storage).
    private static void applyPackageFilter(Predicate<PackageParser.Package> filter,
            Collection<PackageParser.Package> result,
            Collection<PackageParser.Package> packages,
            @NonNull List<PackageParser.Package> sortTemp,
            PackageManagerService packageManagerService) {
        for (PackageParser.Package pkg : packages) {
            if (filter.test(pkg)) {
                sortTemp.add(pkg);
            }
        }

        sortPackagesByUsageDate(sortTemp, packageManagerService);
        packages.removeAll(sortTemp);

        for (PackageParser.Package pkg : sortTemp) {
            result.add(pkg);

            Collection<PackageParser.Package> deps =
                    packageManagerService.findSharedNonSystemLibraries(pkg);
            if (!deps.isEmpty()) {
                deps.removeAll(result);
                result.addAll(deps);
                packages.removeAll(deps);
            }
        }

        sortTemp.clear();
    }

    // Sort apps by importance for dexopt ordering. Important apps are given
    // more priority in case the device runs out of space.
    public static List<PackageParser.Package> getPackagesForDexopt(
            Collection<PackageParser.Package> packages,
            PackageManagerService packageManagerService) {
        ArrayList<PackageParser.Package> remainingPkgs = new ArrayList<>(packages);
        LinkedList<PackageParser.Package> result = new LinkedList<>();
        ArrayList<PackageParser.Package> sortTemp = new ArrayList<>(remainingPkgs.size());

        // Give priority to core apps.
        applyPackageFilter((pkg) -> pkg.coreApp, result, remainingPkgs, sortTemp,
                packageManagerService);

        // Give priority to system apps that listen for pre boot complete.
        Intent intent = new Intent(Intent.ACTION_PRE_BOOT_COMPLETED);
        final ArraySet<String> pkgNames = getPackageNamesForIntent(intent, UserHandle.USER_SYSTEM);
        applyPackageFilter((pkg) -> pkgNames.contains(pkg.packageName), result, remainingPkgs,
                sortTemp, packageManagerService);

        // Give priority to apps used by other apps.
        DexManager dexManager = packageManagerService.getDexManager();
        applyPackageFilter((pkg) ->
                dexManager.getPackageUseInfoOrDefault(pkg.packageName)
                        .isAnyCodePathUsedByOtherApps(),
                result, remainingPkgs, sortTemp, packageManagerService);

        // Filter out packages that aren't recently used, add all remaining apps.
        // TODO: add a property to control this?
        Predicate<PackageParser.Package> remainingPredicate;
        if (!remainingPkgs.isEmpty() && packageManagerService.isHistoricalPackageUsageAvailable()) {
            if (DEBUG_DEXOPT) {
                Log.i(TAG, "Looking at historical package use");
            }
            // Get the package that was used last.
            PackageParser.Package lastUsed = Collections.max(remainingPkgs, (pkg1, pkg2) ->
                    Long.compare(pkg1.getLatestForegroundPackageUseTimeInMills(),
                            pkg2.getLatestForegroundPackageUseTimeInMills()));
            if (DEBUG_DEXOPT) {
                Log.i(TAG, "Taking package " + lastUsed.packageName + " as reference in time use");
            }
            long estimatedPreviousSystemUseTime =
                    lastUsed.getLatestForegroundPackageUseTimeInMills();
            // Be defensive if for some reason package usage has bogus data.
            if (estimatedPreviousSystemUseTime != 0) {
                final long cutoffTime = estimatedPreviousSystemUseTime - SEVEN_DAYS_IN_MILLISECONDS;
                remainingPredicate =
                        (pkg) -> pkg.getLatestForegroundPackageUseTimeInMills() >= cutoffTime;
            } else {
                // No meaningful historical info. Take all.
                remainingPredicate = (pkg) -> true;
            }
            sortPackagesByUsageDate(remainingPkgs, packageManagerService);
        } else {
            // No historical info. Take all.
            remainingPredicate = (pkg) -> true;
        }
        applyPackageFilter(remainingPredicate, result, remainingPkgs, sortTemp,
                packageManagerService);

        if (DEBUG_DEXOPT) {
            Log.i(TAG, "Packages to be dexopted: " + packagesToString(result));
            Log.i(TAG, "Packages skipped from dexopt: " + packagesToString(remainingPkgs));
        }

        return result;
    }

    /**
     * Checks if the package was inactive during since <code>thresholdTimeinMillis</code>.
     * Package is considered active, if:
     * 1) It was active in foreground.
     * 2) It was active in background and also used by other apps.
     *
     * If it doesn't have sufficient information about the package, it return <code>false</code>.
     */
    public static boolean isUnusedSinceTimeInMillis(long firstInstallTime, long currentTimeInMillis,
            long thresholdTimeinMillis, PackageDexUsage.PackageUseInfo packageUseInfo,
            long latestPackageUseTimeInMillis, long latestForegroundPackageUseTimeInMillis) {

        if (currentTimeInMillis - firstInstallTime < thresholdTimeinMillis) {
            return false;
        }

        // If the app was active in foreground during the threshold period.
        boolean isActiveInForeground = (currentTimeInMillis
                - latestForegroundPackageUseTimeInMillis)
                < thresholdTimeinMillis;

        if (isActiveInForeground) {
            return false;
        }

        // If the app was active in background during the threshold period and was used
        // by other packages.
        boolean isActiveInBackgroundAndUsedByOtherPackages = ((currentTimeInMillis
                - latestPackageUseTimeInMillis)
                < thresholdTimeinMillis)
                && packageUseInfo.isAnyCodePathUsedByOtherApps();

        return !isActiveInBackgroundAndUsedByOtherPackages;
    }

    /**
     * Returns the canonicalized path of {@code path} as per {@code realpath(3)}
     * semantics.
     */
    public static String realpath(File path) throws IOException {
        try {
            return Os.realpath(path.getAbsolutePath());
        } catch (ErrnoException ee) {
            throw ee.rethrowAsIOException();
        }
    }

    public static String packagesToString(Collection<PackageParser.Package> c) {
        StringBuilder sb = new StringBuilder();
        for (PackageParser.Package pkg : c) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(pkg.packageName);
        }
        return sb.toString();
    }

    /**
     * Verifies that the given string {@code isa} is a valid supported isa on
     * the running device.
     */
    public static boolean checkISA(String isa) {
        for (String abi : Build.SUPPORTED_ABIS) {
            if (VMRuntime.getInstructionSet(abi).equals(isa)) {
                return true;
            }
        }
        return false;
    }

    public static long getLastModifiedTime(PackageParser.Package pkg) {
        final File srcFile = new File(pkg.codePath);
        if (!srcFile.isDirectory()) {
            return srcFile.lastModified();
        }
        final File baseFile = new File(pkg.baseCodePath);
        long maxModifiedTime = baseFile.lastModified();
        if (pkg.splitCodePaths != null) {
            for (int i = pkg.splitCodePaths.length - 1; i >=0; --i) {
                final File splitFile = new File(pkg.splitCodePaths[i]);
                maxModifiedTime = Math.max(maxModifiedTime, splitFile.lastModified());
            }
        }
        return maxModifiedTime;
    }

    private static File getSettingsProblemFile() {
        File dataDir = Environment.getDataDirectory();
        File systemDir = new File(dataDir, "system");
        File fname = new File(systemDir, "uiderrors.txt");
        return fname;
    }

    public static void dumpCriticalInfo(ProtoOutputStream proto) {
        try (BufferedReader in = new BufferedReader(new FileReader(getSettingsProblemFile()))) {
            String line = null;
            while ((line = in.readLine()) != null) {
                if (line.contains("ignored: updated version")) continue;
                proto.write(PackageServiceDumpProto.MESSAGES, line);
            }
        } catch (IOException ignored) {
        }
    }

    public static void dumpCriticalInfo(PrintWriter pw, String msg) {
        try (BufferedReader in = new BufferedReader(new FileReader(getSettingsProblemFile()))) {
            String line = null;
            while ((line = in.readLine()) != null) {
                if (line.contains("ignored: updated version")) continue;
                if (msg != null) {
                    pw.print(msg);
                }
                pw.println(line);
            }
        } catch (IOException ignored) {
        }
    }

    public static void logCriticalInfo(int priority, String msg) {
        Slog.println(priority, TAG, msg);
        EventLogTags.writePmCriticalInfo(msg);
        try {
            File fname = getSettingsProblemFile();
            FileOutputStream out = new FileOutputStream(fname, true);
            PrintWriter pw = new FastPrintWriter(out);
            SimpleDateFormat formatter = new SimpleDateFormat();
            String dateString = formatter.format(new Date(System.currentTimeMillis()));
            pw.println(dateString + ": " + msg);
            pw.close();
            FileUtils.setPermissions(
                    fname.toString(),
                    FileUtils.S_IRWXU|FileUtils.S_IRWXG|FileUtils.S_IROTH,
                    -1, -1);
        } catch (java.io.IOException e) {
        }
    }

    public static void enforceShellRestriction(String restriction, int callingUid, int userHandle) {
        if (callingUid == Process.SHELL_UID) {
            if (userHandle >= 0
                    && PackageManagerService.sUserManager.hasUserRestriction(
                            restriction, userHandle)) {
                throw new SecurityException("Shell does not have permission to access user "
                        + userHandle);
            } else if (userHandle < 0) {
                Slog.e(PackageManagerService.TAG, "Unable to check shell permission for user "
                        + userHandle + "\n\t" + Debug.getCallers(3));
            }
        }
    }

    /**
     * Derive the value of the {@code cpuAbiOverride} based on the provided
     * value and an optional stored value from the package settings.
     */
    public static String deriveAbiOverride(String abiOverride, PackageSetting settings) {
        String cpuAbiOverride = null;
        if (NativeLibraryHelper.CLEAR_ABI_OVERRIDE.equals(abiOverride)) {
            cpuAbiOverride = null;
        } else if (abiOverride != null) {
            cpuAbiOverride = abiOverride;
        } else if (settings != null) {
            cpuAbiOverride = settings.cpuAbiOverrideString;
        }
        return cpuAbiOverride;
    }

    /**
     * Compares two sets of signatures. Returns:
     * <br />
     * {@link PackageManager#SIGNATURE_NEITHER_SIGNED}: if both signature sets are null,
     * <br />
     * {@link PackageManager#SIGNATURE_FIRST_NOT_SIGNED}: if the first signature set is null,
     * <br />
     * {@link PackageManager#SIGNATURE_SECOND_NOT_SIGNED}: if the second signature set is null,
     * <br />
     * {@link PackageManager#SIGNATURE_MATCH}: if the two signature sets are identical,
     * <br />
     * {@link PackageManager#SIGNATURE_NO_MATCH}: if the two signature sets differ.
     */
    public static int compareSignatures(Signature[] s1, Signature[] s2) {
        if (s1 == null) {
            return s2 == null
                    ? PackageManager.SIGNATURE_NEITHER_SIGNED
                    : PackageManager.SIGNATURE_FIRST_NOT_SIGNED;
        }

        if (s2 == null) {
            return PackageManager.SIGNATURE_SECOND_NOT_SIGNED;
        }

        if (s1.length != s2.length) {
            return PackageManager.SIGNATURE_NO_MATCH;
        }

        // Since both signature sets are of size 1, we can compare without HashSets.
        if (s1.length == 1) {
            return s1[0].equals(s2[0]) ?
                    PackageManager.SIGNATURE_MATCH :
                    PackageManager.SIGNATURE_NO_MATCH;
        }

        ArraySet<Signature> set1 = new ArraySet<Signature>();
        for (Signature sig : s1) {
            set1.add(sig);
        }
        ArraySet<Signature> set2 = new ArraySet<Signature>();
        for (Signature sig : s2) {
            set2.add(sig);
        }
        // Make sure s2 contains all signatures in s1.
        if (set1.equals(set2)) {
            return PackageManager.SIGNATURE_MATCH;
        }
        return PackageManager.SIGNATURE_NO_MATCH;
    }

    /**
     * Used for backward compatibility to make sure any packages with
     * certificate chains get upgraded to the new style. {@code existingSigs}
     * will be in the old format (since they were stored on disk from before the
     * system upgrade) and {@code scannedSigs} will be in the newer format.
     */
    private static boolean matchSignaturesCompat(String packageName,
            PackageSignatures packageSignatures, PackageParser.SigningDetails parsedSignatures) {
        ArraySet<Signature> existingSet = new ArraySet<Signature>();
        for (Signature sig : packageSignatures.mSigningDetails.signatures) {
            existingSet.add(sig);
        }
        ArraySet<Signature> scannedCompatSet = new ArraySet<Signature>();
        for (Signature sig : parsedSignatures.signatures) {
            try {
                Signature[] chainSignatures = sig.getChainSignatures();
                for (Signature chainSig : chainSignatures) {
                    scannedCompatSet.add(chainSig);
                }
            } catch (CertificateEncodingException e) {
                scannedCompatSet.add(sig);
            }
        }
        // make sure the expanded scanned set contains all signatures in the existing one
        if (scannedCompatSet.equals(existingSet)) {
            // migrate the old signatures to the new scheme
            packageSignatures.mSigningDetails = parsedSignatures;
            return true;
        } else if (parsedSignatures.hasPastSigningCertificates()) {

            // well this sucks: the parsed package has probably rotated signing certificates, but
            // we don't have enough information to determine if the new signing certificate was
            // blessed by the old one
            logCriticalInfo(Log.INFO, "Existing package " + packageName + " has flattened signing "
                    + "certificate chain. Unable to install newer version with rotated signing "
                    + "certificate.");
        }
        return false;
    }

    private static boolean matchSignaturesRecover(
            String packageName,
            PackageParser.SigningDetails existingSignatures,
            PackageParser.SigningDetails parsedSignatures,
            @PackageParser.SigningDetails.CertCapabilities int flags) {
        String msg = null;
        try {
            if (parsedSignatures.checkCapabilityRecover(existingSignatures, flags)) {
                logCriticalInfo(Log.INFO, "Recovered effectively matching certificates for "
                        + packageName);
                    return true;
            }
        } catch (CertificateException e) {
            msg = e.getMessage();
        }
        logCriticalInfo(Log.INFO,
                "Failed to recover certificates for " + packageName + ": " + msg);
        return false;
    }

    /**
     * Make sure the updated priv app is signed with the same key as the original APK file on the
     * /system partition.
     *
     * <p>The rationale is that {@code disabledPkg} is a PackageSetting backed by xml files in /data
     * and is not tamperproof.
     */
    private static boolean matchSignatureInSystem(PackageSetting pkgSetting,
            PackageSetting disabledPkgSetting) {
        try {
            PackageParser.collectCertificates(disabledPkgSetting.pkg, true /* skipVerify */);
            if (pkgSetting.signatures.mSigningDetails.checkCapability(
                    disabledPkgSetting.signatures.mSigningDetails,
                    PackageParser.SigningDetails.CertCapabilities.INSTALLED_DATA)
                    || disabledPkgSetting.signatures.mSigningDetails.checkCapability(
                            pkgSetting.signatures.mSigningDetails,
                            PackageParser.SigningDetails.CertCapabilities.ROLLBACK)) {
                return true;
            } else {
                logCriticalInfo(Log.ERROR, "Updated system app mismatches cert on /system: " +
                        pkgSetting.name);
                return false;
            }
        } catch (PackageParserException e) {
            logCriticalInfo(Log.ERROR, "Failed to collect cert for " + pkgSetting.name + ": " +
                    e.getMessage());
            return false;
        }
    }

    /** Returns true if APK Verity is enabled. */
    static boolean isApkVerityEnabled() {
        return SystemProperties.getInt("ro.apk_verity.mode", 0) != 0;
    }

    /** Returns true to force apk verification if the updated package (in /data) is a priv app. */
    static boolean isApkVerificationForced(@Nullable PackageSetting disabledPs) {
        return disabledPs != null && disabledPs.isPrivileged() && isApkVerityEnabled();
    }

    /**
     * Verifies that signatures match.
     * @returns {@code true} if the compat signatures were matched; otherwise, {@code false}.
     * @throws PackageManagerException if the signatures did not match.
     */
    public static boolean verifySignatures(PackageSetting pkgSetting,
            PackageSetting disabledPkgSetting, PackageParser.SigningDetails parsedSignatures,
            boolean compareCompat, boolean compareRecover)
            throws PackageManagerException {
        final String packageName = pkgSetting.name;
        boolean compatMatch = false;
        if (pkgSetting.signatures.mSigningDetails.signatures != null) {

            // Already existing package. Make sure signatures match
            boolean match = parsedSignatures.checkCapability(
                    pkgSetting.signatures.mSigningDetails,
                    PackageParser.SigningDetails.CertCapabilities.INSTALLED_DATA)
                            || pkgSetting.signatures.mSigningDetails.checkCapability(
                                    parsedSignatures,
                                    PackageParser.SigningDetails.CertCapabilities.ROLLBACK);
            if (!match && compareCompat) {
                match = matchSignaturesCompat(packageName, pkgSetting.signatures,
                        parsedSignatures);
                compatMatch = match;
            }
            if (!match && compareRecover) {
                match = matchSignaturesRecover(
                        packageName,
                        pkgSetting.signatures.mSigningDetails,
                        parsedSignatures,
                        PackageParser.SigningDetails.CertCapabilities.INSTALLED_DATA)
                                || matchSignaturesRecover(
                                        packageName,
                                        parsedSignatures,
                                        pkgSetting.signatures.mSigningDetails,
                                        PackageParser.SigningDetails.CertCapabilities.ROLLBACK);
            }

            if (!match && isApkVerificationForced(disabledPkgSetting)) {
                match = matchSignatureInSystem(pkgSetting, disabledPkgSetting);
            }

            if (!match) {
                throw new PackageManagerException(INSTALL_FAILED_UPDATE_INCOMPATIBLE,
                        "Package " + packageName +
                        " signatures do not match previously installed version; ignoring!");
            }
        }
        // Check for shared user signatures
        if (pkgSetting.sharedUser != null
                && pkgSetting.sharedUser.signatures.mSigningDetails
                        != PackageParser.SigningDetails.UNKNOWN) {

            // Already existing package. Make sure signatures match.  In case of signing certificate
            // rotation, the packages with newer certs need to be ok with being sharedUserId with
            // the older ones.  We check to see if either the new package is signed by an older cert
            // with which the current sharedUser is ok, or if it is signed by a newer one, and is ok
            // with being sharedUser with the existing signing cert.
            boolean match =
                    parsedSignatures.checkCapability(
                            pkgSetting.sharedUser.signatures.mSigningDetails,
                            PackageParser.SigningDetails.CertCapabilities.SHARED_USER_ID)
                    || pkgSetting.sharedUser.signatures.mSigningDetails.checkCapability(
                            parsedSignatures,
                            PackageParser.SigningDetails.CertCapabilities.SHARED_USER_ID);
            if (!match && compareCompat) {
                match = matchSignaturesCompat(
                        packageName, pkgSetting.sharedUser.signatures, parsedSignatures);
            }
            if (!match && compareRecover) {
                match =
                        matchSignaturesRecover(packageName,
                                pkgSetting.sharedUser.signatures.mSigningDetails,
                                parsedSignatures,
                                PackageParser.SigningDetails.CertCapabilities.SHARED_USER_ID)
                        || matchSignaturesRecover(packageName,
                                parsedSignatures,
                                pkgSetting.sharedUser.signatures.mSigningDetails,
                                PackageParser.SigningDetails.CertCapabilities.SHARED_USER_ID);
                compatMatch |= match;
            }
            if (!match) {
                throw new PackageManagerException(INSTALL_FAILED_SHARED_USER_INCOMPATIBLE,
                        "Package " + packageName
                        + " has no signatures that match those in shared user "
                        + pkgSetting.sharedUser.name + "; ignoring!");
            }
        }
        return compatMatch;
    }

    public static int decompressFile(File srcFile, File dstFile) throws ErrnoException {
        if (DEBUG_COMPRESSION) {
            Slog.i(TAG, "Decompress file"
                    + "; src: " + srcFile.getAbsolutePath()
                    + ", dst: " + dstFile.getAbsolutePath());
        }
        try (
                InputStream fileIn = new GZIPInputStream(new FileInputStream(srcFile));
                OutputStream fileOut = new FileOutputStream(dstFile, false /*append*/);
        ) {
            FileUtils.copy(fileIn, fileOut);
            Os.chmod(dstFile.getAbsolutePath(), 0644);
            return PackageManager.INSTALL_SUCCEEDED;
        } catch (IOException e) {
            logCriticalInfo(Log.ERROR, "Failed to decompress file"
                    + "; src: " + srcFile.getAbsolutePath()
                    + ", dst: " + dstFile.getAbsolutePath());
        }
        return PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
    }

    public static File[] getCompressedFiles(String codePath) {
        final File stubCodePath = new File(codePath);
        final String stubName = stubCodePath.getName();

        // The layout of a compressed package on a given partition is as follows :
        //
        // Compressed artifacts:
        //
        // /partition/ModuleName/foo.gz
        // /partation/ModuleName/bar.gz
        //
        // Stub artifact:
        //
        // /partition/ModuleName-Stub/ModuleName-Stub.apk
        //
        // In other words, stub is on the same partition as the compressed artifacts
        // and in a directory that's suffixed with "-Stub".
        int idx = stubName.lastIndexOf(STUB_SUFFIX);
        if (idx < 0 || (stubName.length() != (idx + STUB_SUFFIX.length()))) {
            return null;
        }

        final File stubParentDir = stubCodePath.getParentFile();
        if (stubParentDir == null) {
            Slog.e(TAG, "Unable to determine stub parent dir for codePath: " + codePath);
            return null;
        }

        final File compressedPath = new File(stubParentDir, stubName.substring(0, idx));
        final File[] files = compressedPath.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.toLowerCase().endsWith(COMPRESSED_EXTENSION);
            }
        });

        if (DEBUG_COMPRESSION && files != null && files.length > 0) {
            Slog.i(TAG, "getCompressedFiles[" + codePath + "]: " + Arrays.toString(files));
        }

        return files;
    }

    public static boolean compressedFileExists(String codePath) {
        final File[] compressedFiles = getCompressedFiles(codePath);
        return compressedFiles != null && compressedFiles.length > 0;
    }
}
