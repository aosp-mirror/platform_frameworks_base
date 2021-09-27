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
import static android.system.OsConstants.O_CREAT;
import static android.system.OsConstants.O_RDWR;

import static com.android.server.pm.PackageManagerService.COMPRESSED_EXTENSION;
import static com.android.server.pm.PackageManagerService.DEBUG_COMPRESSION;
import static com.android.server.pm.PackageManagerService.DEBUG_DEXOPT;
import static com.android.server.pm.PackageManagerService.DEBUG_INTENT_MATCHING;
import static com.android.server.pm.PackageManagerService.DEBUG_PREFERRED;
import static com.android.server.pm.PackageManagerService.RANDOM_DIR_PREFIX;
import static com.android.server.pm.PackageManagerService.STUB_SUFFIX;
import static com.android.server.pm.PackageManagerService.TAG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppGlobals;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfoLite;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.content.pm.parsing.ApkLiteParseUtils;
import android.content.pm.parsing.PackageLite;
import android.content.pm.parsing.component.ParsedMainComponent;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.os.Build;
import android.os.Debug;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.incremental.IncrementalManager;
import android.os.incremental.V4Signature;
import android.os.incremental.V4Signature.HashingInfo;
import android.os.storage.DiskInfo;
import android.os.storage.VolumeInfo;
import android.service.pm.PackageServiceDumpProto;
import android.stats.storage.StorageEnums;
import android.system.ErrnoException;
import android.system.Os;
import android.util.ArraySet;
import android.util.Base64;
import android.util.Log;
import android.util.LogPrinter;
import android.util.Printer;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.content.NativeLibraryHelper;
import com.android.internal.content.PackageHelper;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FastPrintWriter;
import com.android.internal.util.HexDump;
import com.android.server.EventLogTags;
import com.android.server.IntentResolver;
import com.android.server.compat.PlatformCompat;
import com.android.server.pm.dex.DexManager;
import com.android.server.pm.dex.PackageDexUsage;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.verify.domain.DomainVerificationManagerInternal;
import com.android.server.utils.WatchedLongSparseArray;

import dalvik.system.VMRuntime;

import libcore.io.IoUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.security.SecureRandom;
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
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;

/**
 * Class containing helper methods for the PackageManagerService.
 *
 * {@hide}
 */
public class PackageManagerServiceUtils {
    private static final long SEVEN_DAYS_IN_MILLISECONDS = 7 * 24 * 60 * 60 * 1000;
    private static final long MAX_CRITICAL_INFO_DUMP_SIZE = 3 * 1000 * 1000; // 3MB

    public final static Predicate<PackageSetting> REMOVE_IF_NULL_PKG =
            pkgSetting -> pkgSetting.pkg == null;

    /**
     * Components of apps targeting Android T and above will stop receiving intents from
     * external callers that do not match its declared intent filters.
     *
     * When an app registers an exported component in its manifest and adds an <intent-filter>,
     * the component can be started by any intent - even those that do not match the intent filter.
     * This has proven to be something that many developers find counterintuitive.
     * Without checking the intent when the component is started, in some circumstances this can
     * allow 3P apps to trigger internal-only functionality.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.S)
    private static final long ENFORCE_INTENTS_TO_MATCH_INTENT_FILTERS = 161252188;

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
    public static void sortPackagesByUsageDate(List<PackageSetting> pkgSettings,
            PackageManagerService packageManagerService) {
        if (!packageManagerService.isHistoricalPackageUsageAvailable()) {
            return;
        }

        Collections.sort(pkgSettings, (pkgSetting1, pkgSetting2) ->
                Long.compare(
                        pkgSetting2.getPkgState().getLatestForegroundPackageUseTimeInMills(),
                        pkgSetting1.getPkgState().getLatestForegroundPackageUseTimeInMills())
        );
    }

    // Apply the given {@code filter} to all packages in {@code packages}. If tested positive, the
    // package will be removed from {@code packages} and added to {@code result} with its
    // dependencies. If usage data is available, the positive packages will be sorted by usage
    // data (with {@code sortTemp} as temporary storage).
    private static void applyPackageFilter(
            Predicate<PackageSetting> filter,
            Collection<PackageSetting> result,
            Collection<PackageSetting> packages,
            @NonNull List<PackageSetting> sortTemp,
            PackageManagerService packageManagerService) {
        for (PackageSetting pkgSetting : packages) {
            if (filter.test(pkgSetting)) {
                sortTemp.add(pkgSetting);
            }
        }

        sortPackagesByUsageDate(sortTemp, packageManagerService);
        packages.removeAll(sortTemp);

        for (PackageSetting pkgSetting : sortTemp) {
            result.add(pkgSetting);

            List<PackageSetting> deps =
                    packageManagerService.findSharedNonSystemLibraries(pkgSetting);
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
    public static List<PackageSetting> getPackagesForDexopt(
            Collection<PackageSetting> packages,
            PackageManagerService packageManagerService) {
        return getPackagesForDexopt(packages, packageManagerService, DEBUG_DEXOPT);
    }

    public static List<PackageSetting> getPackagesForDexopt(
            Collection<PackageSetting> pkgSettings,
            PackageManagerService packageManagerService,
            boolean debug) {
        List<PackageSetting> result = new LinkedList<>();
        ArrayList<PackageSetting> remainingPkgSettings = new ArrayList<>(pkgSettings);

        // First, remove all settings without available packages
        remainingPkgSettings.removeIf(REMOVE_IF_NULL_PKG);

        ArrayList<PackageSetting> sortTemp = new ArrayList<>(remainingPkgSettings.size());

        // Give priority to core apps.
        applyPackageFilter(pkgSetting -> pkgSetting.pkg.isCoreApp(), result, remainingPkgSettings, sortTemp,
                packageManagerService);

        // Give priority to system apps that listen for pre boot complete.
        Intent intent = new Intent(Intent.ACTION_PRE_BOOT_COMPLETED);
        final ArraySet<String> pkgNames = getPackageNamesForIntent(intent, UserHandle.USER_SYSTEM);
        applyPackageFilter(pkgSetting -> pkgNames.contains(pkgSetting.name), result,
                remainingPkgSettings, sortTemp, packageManagerService);

        // Give priority to apps used by other apps.
        DexManager dexManager = packageManagerService.getDexManager();
        applyPackageFilter(pkgSetting ->
                dexManager.getPackageUseInfoOrDefault(pkgSetting.name)
                        .isAnyCodePathUsedByOtherApps(),
                result, remainingPkgSettings, sortTemp, packageManagerService);

        // Filter out packages that aren't recently used, add all remaining apps.
        // TODO: add a property to control this?
        Predicate<PackageSetting> remainingPredicate;
        if (!remainingPkgSettings.isEmpty() && packageManagerService.isHistoricalPackageUsageAvailable()) {
            if (debug) {
                Log.i(TAG, "Looking at historical package use");
            }
            // Get the package that was used last.
            PackageSetting lastUsed = Collections.max(remainingPkgSettings,
                    (pkgSetting1, pkgSetting2) -> Long.compare(
                            pkgSetting1.getPkgState().getLatestForegroundPackageUseTimeInMills(),
                            pkgSetting2.getPkgState().getLatestForegroundPackageUseTimeInMills()));
            if (debug) {
                Log.i(TAG, "Taking package " + lastUsed.name
                        + " as reference in time use");
            }
            long estimatedPreviousSystemUseTime = lastUsed.getPkgState()
                    .getLatestForegroundPackageUseTimeInMills();
            // Be defensive if for some reason package usage has bogus data.
            if (estimatedPreviousSystemUseTime != 0) {
                final long cutoffTime = estimatedPreviousSystemUseTime - SEVEN_DAYS_IN_MILLISECONDS;
                remainingPredicate = pkgSetting -> pkgSetting.getPkgState()
                        .getLatestForegroundPackageUseTimeInMills() >= cutoffTime;
            } else {
                // No meaningful historical info. Take all.
                remainingPredicate = pkgSetting -> true;
            }
            sortPackagesByUsageDate(remainingPkgSettings, packageManagerService);
        } else {
            // No historical info. Take all.
            remainingPredicate = pkgSetting -> true;
        }
        applyPackageFilter(remainingPredicate, result, remainingPkgSettings, sortTemp,
                packageManagerService);

        if (debug) {
            Log.i(TAG, "Packages to be dexopted: " + packagesToString(result));
            Log.i(TAG, "Packages skipped from dexopt: " + packagesToString(remainingPkgSettings));
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

    public static String packagesToString(List<PackageSetting> pkgSettings) {
        StringBuilder sb = new StringBuilder();
        for (int index = 0; index < pkgSettings.size(); index++) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(pkgSettings.get(index).name);
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

    public static long getLastModifiedTime(AndroidPackage pkg) {
        final File srcFile = new File(pkg.getPath());
        if (!srcFile.isDirectory()) {
            return srcFile.lastModified();
        }
        final File baseFile = new File(pkg.getBaseApkPath());
        long maxModifiedTime = baseFile.lastModified();
        if (pkg.getSplitCodePaths() != null) {
            for (int i = pkg.getSplitCodePaths().length - 1; i >=0; --i) {
                final File splitFile = new File(pkg.getSplitCodePaths()[i]);
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
        final File file = getSettingsProblemFile();
        final long skipSize = file.length() - MAX_CRITICAL_INFO_DUMP_SIZE;
        try (BufferedReader in = new BufferedReader(new FileReader(file))) {
            if (skipSize > 0) {
                in.skip(skipSize);
            }
            String line = null;
            while ((line = in.readLine()) != null) {
                if (line.contains("ignored: updated version")) continue;
                proto.write(PackageServiceDumpProto.MESSAGES, line);
            }
        } catch (IOException ignored) {
        }
    }

    public static void dumpCriticalInfo(PrintWriter pw, String msg) {
        final File file = getSettingsProblemFile();
        final long skipSize = file.length() - MAX_CRITICAL_INFO_DUMP_SIZE;
        try (BufferedReader in = new BufferedReader(new FileReader(file))) {
            if (skipSize > 0) {
                in.skip(skipSize);
            }
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

    /** Enforces that if the caller is shell, it does not have the provided user restriction. */
    public static void enforceShellRestriction(
            UserManagerInternal userManager, String restriction, int callingUid, int userHandle) {
        if (callingUid == Process.SHELL_UID) {
            if (userHandle >= 0
                    && userManager.hasUserRestriction(
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
     * Enforces that the caller must be either the system process or the phone process.
     * If not, throws a {@link SecurityException}.
     */
    public static void enforceSystemOrPhoneCaller(String methodName, int callingUid) {
        if (callingUid != Process.PHONE_UID && callingUid != Process.SYSTEM_UID) {
            throw new SecurityException(
                    "Cannot call " + methodName + " from UID " + callingUid);
        }
    }

    /**
     * Derive the value of the {@code cpuAbiOverride} based on the provided
     * value.
     */
    public static String deriveAbiOverride(String abiOverride) {
        if (NativeLibraryHelper.CLEAR_ABI_OVERRIDE.equals(abiOverride)) {
            return null;
        }
        return abiOverride;
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
     * Returns true if the signature set of the package is identical to the specified signature
     * set or if the signing details of the package are unknown.
     */
    public static boolean comparePackageSignatures(PackageSetting pkgSetting,
            Signature[] signatures) {
        final SigningDetails signingDetails = pkgSetting.signatures.mSigningDetails;
        return signingDetails == SigningDetails.UNKNOWN
                || compareSignatures(signingDetails.getSignatures(), signatures)
                == PackageManager.SIGNATURE_MATCH;
    }

    /**
     * Used for backward compatibility to make sure any packages with
     * certificate chains get upgraded to the new style. {@code existingSigs}
     * will be in the old format (since they were stored on disk from before the
     * system upgrade) and {@code scannedSigs} will be in the newer format.
     */
    private static boolean matchSignaturesCompat(String packageName,
            PackageSignatures packageSignatures, SigningDetails parsedSignatures) {
        ArraySet<Signature> existingSet = new ArraySet<Signature>();
        for (Signature sig : packageSignatures.mSigningDetails.getSignatures()) {
            existingSet.add(sig);
        }
        ArraySet<Signature> scannedCompatSet = new ArraySet<Signature>();
        for (Signature sig : parsedSignatures.getSignatures()) {
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
            SigningDetails existingSignatures,
            SigningDetails parsedSignatures,
            @SigningDetails.CertCapabilities int flags) {
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
        if (pkgSetting.signatures.mSigningDetails.checkCapability(
                disabledPkgSetting.signatures.mSigningDetails,
                SigningDetails.CertCapabilities.INSTALLED_DATA)
                || disabledPkgSetting.signatures.mSigningDetails.checkCapability(
                pkgSetting.signatures.mSigningDetails,
                SigningDetails.CertCapabilities.ROLLBACK)) {
            return true;
        } else {
            logCriticalInfo(Log.ERROR, "Updated system app mismatches cert on /system: " +
                    pkgSetting.name);
            return false;
        }
    }

    /** Default is to not use fs-verity since it depends on kernel support. */
    private static final int FSVERITY_DISABLED = 0;

    /**
     * Experimental implementation targeting priv apps, with Android specific kernel patches to
     * extend fs-verity.
     */
    private static final int FSVERITY_LEGACY = 1;

    /** Standard fs-verity. */
    private static final int FSVERITY_ENABLED = 2;

    /** Returns true if standard APK Verity is enabled. */
    static boolean isApkVerityEnabled() {
        return Build.VERSION.DEVICE_INITIAL_SDK_INT >= Build.VERSION_CODES.R
                || SystemProperties.getInt("ro.apk_verity.mode", FSVERITY_DISABLED)
                        == FSVERITY_ENABLED;
    }

    static boolean isLegacyApkVerityEnabled() {
        return SystemProperties.getInt("ro.apk_verity.mode", FSVERITY_DISABLED) == FSVERITY_LEGACY;
    }

    /** Returns true to force apk verification if the package is considered privileged. */
    static boolean isApkVerificationForced(@Nullable PackageSetting ps) {
        // TODO(b/154310064): re-enable.
        return false;
    }

    /**
     * Verifies that signatures match.
     * @returns {@code true} if the compat signatures were matched; otherwise, {@code false}.
     * @throws PackageManagerException if the signatures did not match.
     */
    public static boolean verifySignatures(PackageSetting pkgSetting,
            PackageSetting disabledPkgSetting, SigningDetails parsedSignatures,
            boolean compareCompat, boolean compareRecover, boolean isRollback)
            throws PackageManagerException {
        final String packageName = pkgSetting.name;
        boolean compatMatch = false;
        if (pkgSetting.signatures.mSigningDetails.getSignatures() != null) {
            // Already existing package. Make sure signatures match
            boolean match = parsedSignatures.checkCapability(
                    pkgSetting.signatures.mSigningDetails,
                    SigningDetails.CertCapabilities.INSTALLED_DATA)
                            || pkgSetting.signatures.mSigningDetails.checkCapability(
                                    parsedSignatures,
                                    SigningDetails.CertCapabilities.ROLLBACK);
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
                        SigningDetails.CertCapabilities.INSTALLED_DATA)
                                || matchSignaturesRecover(
                                        packageName,
                                        parsedSignatures,
                                        pkgSetting.signatures.mSigningDetails,
                                        SigningDetails.CertCapabilities.ROLLBACK);
            }

            if (!match && isApkVerificationForced(disabledPkgSetting)) {
                match = matchSignatureInSystem(pkgSetting, disabledPkgSetting);
            }

            if (!match && isRollback) {
                // Since a rollback can only be initiated for an APK previously installed on the
                // device allow rolling back to a previous signing key even if the rollback
                // capability has not been granted.
                match = pkgSetting.signatures.mSigningDetails.hasAncestorOrSelf(parsedSignatures);
            }

            if (!match) {
                throw new PackageManagerException(INSTALL_FAILED_UPDATE_INCOMPATIBLE,
                        "Package " + packageName +
                        " signatures do not match previously installed version; ignoring!");
            }
        }
        // Check for shared user signatures
        if (pkgSetting.getSharedUser() != null
                && pkgSetting.getSharedUser().signatures.mSigningDetails
                        != SigningDetails.UNKNOWN) {

            // Already existing package. Make sure signatures match.  In case of signing certificate
            // rotation, the packages with newer certs need to be ok with being sharedUserId with
            // the older ones.  We check to see if either the new package is signed by an older cert
            // with which the current sharedUser is ok, or if it is signed by a newer one, and is ok
            // with being sharedUser with the existing signing cert.
            boolean match =
                    parsedSignatures.checkCapability(
                            pkgSetting.getSharedUser().signatures.mSigningDetails,
                            SigningDetails.CertCapabilities.SHARED_USER_ID)
                    || pkgSetting.getSharedUser().signatures.mSigningDetails.checkCapability(
                            parsedSignatures,
                            SigningDetails.CertCapabilities.SHARED_USER_ID);
            // Special case: if the sharedUserId capability check failed it could be due to this
            // being the only package in the sharedUserId so far and the lineage being updated to
            // deny the sharedUserId capability of the previous key in the lineage.
            if (!match && pkgSetting.getSharedUser().packages.size() == 1
                    && pkgSetting.getSharedUser().packages.valueAt(0).name.equals(packageName)) {
                match = true;
            }
            if (!match && compareCompat) {
                match = matchSignaturesCompat(
                        packageName, pkgSetting.getSharedUser().signatures, parsedSignatures);
            }
            if (!match && compareRecover) {
                match =
                        matchSignaturesRecover(packageName,
                                pkgSetting.getSharedUser().signatures.mSigningDetails,
                                parsedSignatures,
                                SigningDetails.CertCapabilities.SHARED_USER_ID)
                        || matchSignaturesRecover(packageName,
                                parsedSignatures,
                                pkgSetting.getSharedUser().signatures.mSigningDetails,
                                SigningDetails.CertCapabilities.SHARED_USER_ID);
                compatMatch |= match;
            }
            if (!match) {
                throw new PackageManagerException(INSTALL_FAILED_SHARED_USER_INCOMPATIBLE,
                        "Package " + packageName
                        + " has no signatures that match those in shared user "
                        + pkgSetting.getSharedUser().name + "; ignoring!");
            }
            // It is possible that this package contains a lineage that blocks sharedUserId access
            // to an already installed package in the sharedUserId signed with a previous key.
            // Iterate over all of the packages in the sharedUserId and ensure any that are signed
            // with a key in this package's lineage have the SHARED_USER_ID capability granted.
            if (parsedSignatures.hasPastSigningCertificates()) {
                for (PackageSetting shUidPkgSetting : pkgSetting.getSharedUser().packages) {
                    // if the current package in the sharedUserId is the package being updated then
                    // skip this check as the update may revoke the sharedUserId capability from
                    // the key with which this app was previously signed.
                    if (packageName.equals(shUidPkgSetting.name)) {
                        continue;
                    }
                    SigningDetails shUidSigningDetails =
                            shUidPkgSetting.getSigningDetails();
                    // The capability check only needs to be performed against the package if it is
                    // signed with a key that is in the lineage of the package being installed.
                    if (parsedSignatures.hasAncestor(shUidSigningDetails)) {
                        if (!parsedSignatures.checkCapability(shUidSigningDetails,
                                SigningDetails.CertCapabilities.SHARED_USER_ID)) {
                            throw new PackageManagerException(
                                    INSTALL_FAILED_SHARED_USER_INCOMPATIBLE,
                                    "Package " + packageName
                                            + " revoked the sharedUserId capability from the "
                                            + "signing key used to sign " + shUidPkgSetting.name);
                        }
                    }
                }
            }
            // If the lineage of this package diverges from the lineage of the sharedUserId then
            // do not allow the installation to proceed.
            if (!parsedSignatures.hasCommonAncestor(
                    pkgSetting.getSharedUser().signatures.mSigningDetails)) {
                throw new PackageManagerException(INSTALL_FAILED_SHARED_USER_INCOMPATIBLE,
                        "Package " + packageName + " has a signing lineage "
                                + "that diverges from the lineage of the sharedUserId");
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

    /**
     * Parse given package and return minimal details.
     */
    public static PackageInfoLite getMinimalPackageInfo(Context context, PackageLite pkg,
            String packagePath, int flags, String abiOverride) {
        final PackageInfoLite ret = new PackageInfoLite();
        if (packagePath == null || pkg == null) {
            Slog.i(TAG, "Invalid package file " + packagePath);
            ret.recommendedInstallLocation = PackageHelper.RECOMMEND_FAILED_INVALID_APK;
            return ret;
        }

        final File packageFile = new File(packagePath);
        final long sizeBytes;
        try {
            sizeBytes = PackageHelper.calculateInstalledSize(pkg, abiOverride);
        } catch (IOException e) {
            if (!packageFile.exists()) {
                ret.recommendedInstallLocation = PackageHelper.RECOMMEND_FAILED_INVALID_URI;
            } else {
                ret.recommendedInstallLocation = PackageHelper.RECOMMEND_FAILED_INVALID_APK;
            }

            return ret;
        }

        final int recommendedInstallLocation = PackageHelper.resolveInstallLocation(context,
                pkg.getPackageName(), pkg.getInstallLocation(), sizeBytes, flags);

        ret.packageName = pkg.getPackageName();
        ret.splitNames = pkg.getSplitNames();
        ret.versionCode = pkg.getVersionCode();
        ret.versionCodeMajor = pkg.getVersionCodeMajor();
        ret.baseRevisionCode = pkg.getBaseRevisionCode();
        ret.splitRevisionCodes = pkg.getSplitRevisionCodes();
        ret.installLocation = pkg.getInstallLocation();
        ret.verifiers = pkg.getVerifiers();
        ret.recommendedInstallLocation = recommendedInstallLocation;
        ret.multiArch = pkg.isMultiArch();
        ret.debuggable = pkg.isDebuggable();

        return ret;
    }

    /**
     * Calculate estimated footprint of given package post-installation.
     *
     * @return -1 if there's some error calculating the size, otherwise installed size of the
     *         package.
     */
    public static long calculateInstalledSize(String packagePath, String abiOverride) {
        final File packageFile = new File(packagePath);
        try {
            final ParseTypeImpl input = ParseTypeImpl.forDefaultParsing();
            final ParseResult<PackageLite> result = ApkLiteParseUtils.parsePackageLite(
                    input.reset(), packageFile, /* flags */ 0);
            if (result.isError()) {
                throw new PackageManagerException(result.getErrorCode(),
                        result.getErrorMessage(), result.getException());
            }
            return PackageHelper.calculateInstalledSize(result.getResult(), abiOverride);
        } catch (PackageManagerException | IOException e) {
            Slog.w(TAG, "Failed to calculate installed size: " + e);
            return -1;
        }
    }

    /**
     * Checks whenever downgrade of an app is permitted.
     *
     * @param installFlags flags of the current install.
     * @param isAppDebuggable if the currently installed version of the app is debuggable.
     * @return {@code true} if downgrade is permitted according to the {@code installFlags} and
     *         {@code applicationFlags}.
     */
    public static boolean isDowngradePermitted(int installFlags, boolean isAppDebuggable) {
        // If installed, the package will get access to data left on the device by its
        // predecessor. As a security measure, this is permitted only if this is not a
        // version downgrade or if the predecessor package is marked as debuggable and
        // a downgrade is explicitly requested.
        //
        // On debuggable platform builds, downgrades are permitted even for
        // non-debuggable packages to make testing easier. Debuggable platform builds do
        // not offer security guarantees and thus it's OK to disable some security
        // mechanisms to make debugging/testing easier on those builds. However, even on
        // debuggable builds downgrades of packages are permitted only if requested via
        // installFlags. This is because we aim to keep the behavior of debuggable
        // platform builds as close as possible to the behavior of non-debuggable
        // platform builds.
        //
        // In case of user builds, downgrade is permitted only for the system server initiated
        // sessions. This is enforced by INSTALL_ALLOW_DOWNGRADE flag parameter.
        final boolean downgradeRequested =
                (installFlags & PackageManager.INSTALL_REQUEST_DOWNGRADE) != 0;
        if (!downgradeRequested) {
            return false;
        }
        final boolean isDebuggable = Build.IS_DEBUGGABLE || isAppDebuggable;
        if (isDebuggable) {
            return true;
        }
        return (installFlags & PackageManager.INSTALL_ALLOW_DOWNGRADE) != 0;
    }

    /**
     * Copy package to the target location.
     *
     * @param packagePath absolute path to the package to be copied. Can be
     *                    a single monolithic APK file or a cluster directory
     *                    containing one or more APKs.
     * @return returns status code according to those in
     *         {@link PackageManager}
     */
    public static int copyPackage(String packagePath, File targetDir) {
        if (packagePath == null) {
            return PackageManager.INSTALL_FAILED_INVALID_URI;
        }

        try {
            final File packageFile = new File(packagePath);
            final ParseTypeImpl input = ParseTypeImpl.forDefaultParsing();
            final ParseResult<PackageLite> result = ApkLiteParseUtils.parsePackageLite(
                    input.reset(), packageFile, /* flags */ 0);
            if (result.isError()) {
                Slog.w(TAG, "Failed to parse package at " + packagePath);
                return result.getErrorCode();
            }
            final PackageLite pkg = result.getResult();
            copyFile(pkg.getBaseApkPath(), targetDir, "base.apk");
            if (!ArrayUtils.isEmpty(pkg.getSplitNames())) {
                for (int i = 0; i < pkg.getSplitNames().length; i++) {
                    copyFile(pkg.getSplitApkPaths()[i], targetDir,
                            "split_" + pkg.getSplitNames()[i] + ".apk");
                }
            }
            return PackageManager.INSTALL_SUCCEEDED;
        } catch (IOException | ErrnoException e) {
            Slog.w(TAG, "Failed to copy package at " + packagePath + ": " + e);
            return PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
        }
    }

    private static void copyFile(String sourcePath, File targetDir, String targetName)
            throws ErrnoException, IOException {
        if (!FileUtils.isValidExtFilename(targetName)) {
            throw new IllegalArgumentException("Invalid filename: " + targetName);
        }
        Slog.d(TAG, "Copying " + sourcePath + " to " + targetName);

        final File targetFile = new File(targetDir, targetName);
        final FileDescriptor targetFd = Os.open(targetFile.getAbsolutePath(),
                O_RDWR | O_CREAT, 0644);
        Os.chmod(targetFile.getAbsolutePath(), 0644);
        FileInputStream source = null;
        try {
            source = new FileInputStream(sourcePath);
            FileUtils.copy(source.getFD(), targetFd);
        } finally {
            IoUtils.closeQuietly(source);
        }
    }

    /**
     * Recursively create target directory
     */
    public static void makeDirRecursive(File targetDir, int mode) throws ErrnoException {
        final Path targetDirPath = targetDir.toPath();
        final int directoriesCount = targetDirPath.getNameCount();
        File currentDir;
        for (int i = 1; i <= directoriesCount; i++) {
            currentDir = targetDirPath.subpath(0, i).toFile();
            if (currentDir.exists()) {
                continue;
            }
            Os.mkdir(currentDir.getAbsolutePath(), mode);
            Os.chmod(currentDir.getAbsolutePath(), mode);
        }
    }

    /**
     * Returns a string that's compatible with the verification root hash extra.
     * @see PackageManager#EXTRA_VERIFICATION_ROOT_HASH
     */
    @NonNull
    public static String buildVerificationRootHashString(@NonNull String baseFilename,
            @Nullable String[] splitFilenameArray) {
        final StringBuilder sb = new StringBuilder();
        final String baseFilePath =
                baseFilename.substring(baseFilename.lastIndexOf(File.separator) + 1);
        sb.append(baseFilePath).append(":");
        final byte[] baseRootHash = getRootHash(baseFilename);
        if (baseRootHash == null) {
            sb.append("0");
        } else {
            sb.append(HexDump.toHexString(baseRootHash));
        }
        if (splitFilenameArray == null || splitFilenameArray.length == 0) {
            return sb.toString();
        }

        for (int i = splitFilenameArray.length - 1; i >= 0; i--) {
            final String splitFilename = splitFilenameArray[i];
            final String splitFilePath =
                    splitFilename.substring(splitFilename.lastIndexOf(File.separator) + 1);
            final byte[] splitRootHash = getRootHash(splitFilename);
            sb.append(";").append(splitFilePath).append(":");
            if (splitRootHash == null) {
                sb.append("0");
            } else {
                sb.append(HexDump.toHexString(splitRootHash));
            }
        }
        return sb.toString();
    }

    /**
     * Returns the root has for the given file.
     * <p>Otherwise, returns {@code null} if the root hash could not be found or calculated.
     * <p>NOTE: This currently only works on files stored on the incremental file system. The
     * eventual goal is that this hash [among others] can be retrieved for any file.
     */
    @Nullable
    private static byte[] getRootHash(String filename) {
        try {
            final byte[] baseFileSignature =
                    IncrementalManager.unsafeGetFileSignature(filename);
            if (baseFileSignature == null) {
                throw new IOException("File signature not present");
            }
            final V4Signature signature =
                    V4Signature.readFrom(baseFileSignature);
            if (signature.hashingInfo == null) {
                throw new IOException("Hashing info not present");
            }
            final HashingInfo hashInfo =
                    HashingInfo.fromByteArray(signature.hashingInfo);
            if (ArrayUtils.isEmpty(hashInfo.rawRootHash)) {
                throw new IOException("Root has not present");
            }
            return hashInfo.rawRootHash;
        } catch (IOException ignore) {
            Slog.e(TAG, "ERROR: could not load root hash from incremental install");
        }
        return null;
    }

    public static boolean isSystemApp(PackageSetting ps) {
        return (ps.pkgFlags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    public static boolean isUpdatedSystemApp(PackageSetting ps) {
        return (ps.pkgFlags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
    }

    // Static to give access to ComputeEngine
    public static void applyEnforceIntentFilterMatching(
            PlatformCompat compat, ComponentResolver resolver,
            List<ResolveInfo> resolveInfos, boolean isReceiver,
            Intent intent, String resolvedType, int filterCallingUid) {
        // Do not enforce filter matching when the caller is system or root.
        // see ActivityManager#checkComponentPermission(String, int, int, boolean)
        if (filterCallingUid == Process.ROOT_UID || filterCallingUid == Process.SYSTEM_UID) {
            return;
        }

        final Printer logPrinter = DEBUG_INTENT_MATCHING
                ? new LogPrinter(Log.VERBOSE, TAG, Log.LOG_ID_SYSTEM)
                : null;

        for (int i = resolveInfos.size() - 1; i >= 0; --i) {
            final ComponentInfo info = resolveInfos.get(i).getComponentInfo();

            // Do not enforce filter matching when the caller is the same app
            if (info.applicationInfo.uid == filterCallingUid) {
                continue;
            }

            // Only enforce filter matching if target app's target SDK >= T
            if (!compat.isChangeEnabledInternal(
                    ENFORCE_INTENTS_TO_MATCH_INTENT_FILTERS, info.applicationInfo)) {
                continue;
            }

            final ParsedMainComponent comp;
            if (info instanceof ActivityInfo) {
                if (isReceiver) {
                    comp = resolver.getReceiver(info.getComponentName());
                } else {
                    comp = resolver.getActivity(info.getComponentName());
                }
            } else if (info instanceof ServiceInfo) {
                comp = resolver.getService(info.getComponentName());
            } else {
                // This shall never happen
                throw new IllegalArgumentException("Unsupported component type");
            }

            if (comp.getIntents().isEmpty()) {
                continue;
            }

            final boolean match = comp.getIntents().stream().anyMatch(
                    f -> IntentResolver.intentMatchesFilter(f, intent, resolvedType));
            if (!match) {
                Slog.w(TAG, "Intent does not match component's intent filter: " + intent);
                Slog.w(TAG, "Access blocked: " + comp.getComponentName());
                if (DEBUG_INTENT_MATCHING) {
                    Slog.v(TAG, "Component intent filters:");
                    comp.getIntents().forEach(f -> f.dump(logPrinter, "  "));
                    Slog.v(TAG, "-----------------------------");
                }
                resolveInfos.remove(i);
            }
        }
    }


    /**
     * Do NOT use for intent resolution filtering. That should be done with
     * {@link DomainVerificationManagerInternal#filterToApprovedApp(Intent, List, int, Function)}.
     *
     * @return if the package is approved at any non-zero level for the domain in the intent
     */
    public static boolean hasAnyDomainApproval(
            @NonNull DomainVerificationManagerInternal manager, @NonNull PackageSetting pkgSetting,
            @NonNull Intent intent, @PackageManager.ResolveInfoFlags int resolveInfoFlags,
            @UserIdInt int userId) {
        return manager.approvalLevelForDomain(pkgSetting, intent, resolveInfoFlags, userId)
                > DomainVerificationManagerInternal.APPROVAL_LEVEL_NONE;
    }

    /**
     * Update given intent when being used to request {@link ResolveInfo}.
     */
    public static Intent updateIntentForResolve(Intent intent) {
        if (intent.getSelector() != null) {
            intent = intent.getSelector();
        }
        if (DEBUG_PREFERRED) {
            intent.addFlags(Intent.FLAG_DEBUG_LOG_RESOLUTION);
        }
        return intent;
    }

    public static String arrayToString(int[] array) {
        StringBuilder stringBuilder = new StringBuilder(128);
        stringBuilder.append('[');
        if (array != null) {
            for (int i = 0; i < array.length; i++) {
                if (i > 0) stringBuilder.append(", ");
                stringBuilder.append(array[i]);
            }
        }
        stringBuilder.append(']');
        return stringBuilder.toString();
    }

    /**
     * Given {@code targetDir}, returns {@code targetDir/~~[randomStrA]/[packageName]-[randomStrB].}
     * Makes sure that {@code targetDir/~~[randomStrA]} directory doesn't exist.
     * Notice that this method doesn't actually create any directory.
     *
     * @param targetDir Directory that is two-levels up from the result directory.
     * @param packageName Name of the package whose code files are to be installed under the result
     *                    directory.
     * @return File object for the directory that should hold the code files of {@code packageName}.
     */
    public static File getNextCodePath(File targetDir, String packageName) {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[16];
        File firstLevelDir;
        do {
            random.nextBytes(bytes);
            String dirName = RANDOM_DIR_PREFIX
                    + Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP);
            firstLevelDir = new File(targetDir, dirName);
        } while (firstLevelDir.exists());
        random.nextBytes(bytes);
        String suffix = Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP);
        return new File(firstLevelDir, packageName + "-" + suffix);
    }

    /**
     * Gets the type of the external storage a package is installed on.
     * @param packageVolume The storage volume of the package.
     * @param packageIsExternal true if the package is currently installed on
     * external/removable/unprotected storage.
     * @return {@link StorageEnums#UNKNOWN} if the package is not stored externally or the
     * corresponding {@link StorageEnums} storage type value if it is.
     * corresponding {@link StorageEnums} storage type value if it is.
     */
    public static int getPackageExternalStorageType(VolumeInfo packageVolume,
            boolean packageIsExternal) {
        if (packageVolume != null) {
            DiskInfo disk = packageVolume.getDisk();
            if (disk != null) {
                if (disk.isSd()) {
                    return StorageEnums.SD_CARD;
                }
                if (disk.isUsb()) {
                    return StorageEnums.USB;
                }
                if (packageIsExternal) {
                    return StorageEnums.OTHER;
                }
            }
        }
        return StorageEnums.UNKNOWN;
    }
}
