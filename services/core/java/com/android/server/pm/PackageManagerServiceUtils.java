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
import static android.content.pm.PackageManager.INSTALL_FAILED_VERSION_DOWNGRADE;
import static android.content.pm.PackageManager.PROPERTY_ANDROID_SAFETY_LABEL_PATH;
import static android.content.pm.SigningDetails.CertCapabilities.SHARED_USER_ID;
import static android.system.OsConstants.O_CREAT;
import static android.system.OsConstants.O_RDWR;

import static com.android.internal.content.NativeLibraryHelper.LIB64_DIR_NAME;
import static com.android.internal.content.NativeLibraryHelper.LIB_DIR_NAME;
import static com.android.internal.util.FrameworkStatsLog.UNSAFE_INTENT_EVENT_REPORTED__EVENT_TYPE__EXPLICIT_INTENT_FILTER_UNMATCH;
import static com.android.internal.util.FrameworkStatsLog.UNSAFE_INTENT_EVENT_REPORTED__EVENT_TYPE__NULL_ACTION_MATCH;
import static com.android.server.LocalManagerRegistry.ManagerNotFoundException;
import static com.android.server.pm.PackageInstallerSession.APP_METADATA_FILE_ACCESS_MODE;
import static com.android.server.pm.PackageInstallerSession.getAppMetadataSizeLimit;
import static com.android.server.pm.PackageManagerService.COMPRESSED_EXTENSION;
import static com.android.server.pm.PackageManagerService.DEBUG_COMPRESSION;
import static com.android.server.pm.PackageManagerService.DEBUG_INTENT_MATCHING;
import static com.android.server.pm.PackageManagerService.DEBUG_PREFERRED;
import static com.android.server.pm.PackageManagerService.DEFAULT_FILE_ACCESS_MODE;
import static com.android.server.pm.PackageManagerService.DEFAULT_NATIVE_LIBRARY_FILE_ACCESS_MODE;
import static com.android.server.pm.PackageManagerService.RANDOM_CODEPATH_PREFIX;
import static com.android.server.pm.PackageManagerService.RANDOM_DIR_PREFIX;
import static com.android.server.pm.PackageManagerService.SHELL_PACKAGE_NAME;
import static com.android.server.pm.PackageManagerService.STUB_SUFFIX;
import static com.android.server.pm.PackageManagerService.TAG;

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.compat.annotation.ChangeId;
import android.compat.annotation.Disabled;
import android.compat.annotation.Overridable;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfoLite;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.Property;
import android.content.pm.PackagePartitions;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.content.pm.parsing.ApkLiteParseUtils;
import android.content.pm.parsing.PackageLite;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.os.Binder;
import android.os.Build;
import android.os.Debug;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Process;
import android.os.SELinux;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.incremental.IncrementalManager;
import android.os.incremental.IncrementalStorage;
import android.os.incremental.V4Signature;
import android.os.incremental.V4Signature.HashingInfo;
import android.os.storage.DiskInfo;
import android.os.storage.VolumeInfo;
import android.service.pm.PackageServiceDumpProto;
import android.stats.storage.StorageEnums;
import android.system.ErrnoException;
import android.system.Os;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Base64;
import android.util.Log;
import android.util.LogPrinter;
import android.util.Printer;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.content.InstallLocationUtils;
import com.android.internal.content.NativeLibraryHelper;
import com.android.internal.pm.pkg.component.ParsedMainComponent;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FastPrintWriter;
import com.android.internal.util.HexDump;
import com.android.server.EventLogTags;
import com.android.server.IntentResolver;
import com.android.server.LocalManagerRegistry;
import com.android.server.Watchdog;
import com.android.server.am.ActivityManagerUtils;
import com.android.server.compat.PlatformCompat;
import com.android.server.pm.dex.PackageDexUsage;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.AndroidPackageSplit;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.resolution.ComponentResolverApi;
import com.android.server.pm.snapshot.PackageDataSnapshot;
import com.android.server.pm.verify.domain.DomainVerificationManagerInternal;

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
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Class containing helper methods for the PackageManagerService.
 *
 * {@hide}
 */
public class PackageManagerServiceUtils {
    private static final long MAX_CRITICAL_INFO_DUMP_SIZE = 3 * 1000 * 1000; // 3MB

    private static final boolean DEBUG = Build.IS_DEBUGGABLE;

    // Skip APEX which doesn't have a valid UID
    public static final Predicate<PackageStateInternal> REMOVE_IF_APEX_PKG =
            pkgSetting -> pkgSetting.getPkg().isApex();
    public static final Predicate<PackageStateInternal> REMOVE_IF_NULL_PKG =
            pkgSetting -> pkgSetting.getPkg() == null;

    // This is a horrible hack to workaround b/240373119, specifically for fixing the T branch.
    // A proper fix should be implemented in master instead.
    public static final ThreadLocal<Boolean> DISABLE_ENFORCE_INTENTS_TO_MATCH_INTENT_FILTERS =
            ThreadLocal.withInitial(() -> false);

    /**
     * Type used with {@link #canJoinSharedUserId(String, SigningDetails, SharedUserSetting, int)}
     * when the package attempting to join the sharedUserId is a new install.
     */
    public static final int SHARED_USER_ID_JOIN_TYPE_INSTALL = 0;
    /**
     * Type used with {@link #canJoinSharedUserId(String, SigningDetails, SharedUserSetting, int)}
     * when the package attempting to join the sharedUserId is an update.
     */
    public static final int SHARED_USER_ID_JOIN_TYPE_UPDATE = 1;
    /**
     * Type used with {@link #canJoinSharedUserId(String, SigningDetails, SharedUserSetting, int)}
     * when the package attempting to join the sharedUserId is a part of the system image.
     */
    public static final int SHARED_USER_ID_JOIN_TYPE_SYSTEM = 2;
    @IntDef(prefix = { "TYPE_" }, value = {
            SHARED_USER_ID_JOIN_TYPE_INSTALL,
            SHARED_USER_ID_JOIN_TYPE_UPDATE,
            SHARED_USER_ID_JOIN_TYPE_SYSTEM,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SharedUserIdJoinType {}

    /**
     * Intents sent from apps targeting Android V and above will stop resolving to components with
     * non matching intent filters, even when explicitly setting a component name, unless the
     * target components are in the same app as the calling app.
     *
     * When an app registers an exported component in its manifest and adds an <intent-filter>,
     * the component can be started by any intent - even those that do not match the intent filter.
     * This has proven to be something that many developers find counterintuitive.
     * Without checking the intent when the component is started, in some circumstances this can
     * allow 3P apps to trigger internal-only functionality.
     */
    @Overridable
    @ChangeId
    @Disabled
    private static final long ENFORCE_INTENTS_TO_MATCH_INTENT_FILTERS = 161252188;

    /**
     * The initial enabled state of the cache before other checks are done.
     */
    private static final boolean DEFAULT_PACKAGE_PARSER_CACHE_ENABLED = true;

    /**
     * Whether to skip all other checks and force the cache to be enabled.
     *
     * Setting this to true will cause the cache to be named "debug" to avoid eviction from
     * build fingerprint changes.
     */
    private static final boolean FORCE_PACKAGE_PARSED_CACHE_ENABLED = false;

    /**
     * Returns the registered PackageManagerLocal instance, or else throws an unchecked error.
     */
    public static @NonNull PackageManagerLocal getPackageManagerLocal() {
        try {
            return LocalManagerRegistry.getManagerOrThrow(PackageManagerLocal.class);
        } catch (ManagerNotFoundException e) {
            throw new RuntimeException(e);
        }
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
        for (int i = pkg.getSplitCodePaths().length - 1; i >=0; --i) {
            final File splitFile = new File(pkg.getSplitCodePaths()[i]);
            maxModifiedTime = Math.max(maxModifiedTime, splitFile.lastModified());
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
    public static int compareSignatures(SigningDetails sd1, SigningDetails sd2) {
        return compareSignatureArrays(sd1.getSignatures(), sd2.getSignatures());
    }

    static int compareSignatureArrays(Signature[] s1, Signature[] s2) {
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
            SigningDetails otherSigningDetails) {
        final SigningDetails signingDetails = pkgSetting.getSigningDetails();
        return signingDetails == SigningDetails.UNKNOWN
                || compareSignatures(signingDetails, otherSigningDetails)
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
     * Verifies the updated system app has a signature that is consistent with the pre-installed
     * version or the signing lineage.
     */
    private static boolean matchSignatureInSystem(@NonNull String packageName,
            @NonNull SigningDetails signingDetails, PackageSetting disabledPkgSetting) {
        if (signingDetails.checkCapability(
                disabledPkgSetting.getSigningDetails(),
                SigningDetails.CertCapabilities.INSTALLED_DATA)
                || disabledPkgSetting.getSigningDetails().checkCapability(
                signingDetails,
                SigningDetails.CertCapabilities.ROLLBACK)) {
            return true;
        } else {
            logCriticalInfo(Log.ERROR, "Updated system app mismatches cert on /system: " +
                    packageName);
            return false;
        }
    }

    /** Default is to not use fs-verity since it depends on kernel support. */
    private static final int FSVERITY_DISABLED = 0;

    /** Standard fs-verity. */
    private static final int FSVERITY_ENABLED = 2;

    /** Returns true if standard APK Verity is enabled. */
    static boolean isApkVerityEnabled() {
        if (android.security.Flags.deprecateFsvSig()) {
            return false;
        }
        return Build.VERSION.DEVICE_INITIAL_SDK_INT >= Build.VERSION_CODES.R
                || SystemProperties.getInt("ro.apk_verity.mode", FSVERITY_DISABLED)
                        == FSVERITY_ENABLED;
    }

    /**
     * Verifies that signatures match.
     * @returns {@code true} if the compat signatures were matched; otherwise, {@code false}.
     * @throws PackageManagerException if the signatures did not match.
     */
    @SuppressWarnings("ReferenceEquality")
    public static boolean verifySignatures(PackageSetting pkgSetting,
            @Nullable SharedUserSetting sharedUserSetting,
            PackageSetting disabledPkgSetting, SigningDetails parsedSignatures,
            boolean compareCompat, boolean compareRecover, boolean isRollback)
            throws PackageManagerException {
        final String packageName = pkgSetting.getPackageName();
        boolean compatMatch = false;
        if (pkgSetting.getSigningDetails().getSignatures() != null) {
            // For an already existing package, make sure the parsed signatures from the package
            // match the one in PackageSetting.
            boolean match = parsedSignatures.checkCapability(
                    pkgSetting.getSigningDetails(),
                    SigningDetails.CertCapabilities.INSTALLED_DATA)
                            || pkgSetting.getSigningDetails().checkCapability(
                                    parsedSignatures,
                                    SigningDetails.CertCapabilities.ROLLBACK);
            // Also make sure the parsed signatures are consistent with the disabled package
            // setting, if any. The additional UNKNOWN check is because disabled package settings
            // may not have SigningDetails currently, and we don't want to cause an uninstall.
            if (android.security.Flags.extendVbChainToUpdatedApk()
                    && match && disabledPkgSetting != null
                    && disabledPkgSetting.getSigningDetails() != SigningDetails.UNKNOWN) {
                match = matchSignatureInSystem(packageName, parsedSignatures, disabledPkgSetting);
            }

            if (!match && compareCompat) {
                match = matchSignaturesCompat(packageName, pkgSetting.getSignatures(),
                        parsedSignatures);
                compatMatch = match;
            }
            if (!match && compareRecover) {
                match = matchSignaturesRecover(
                        packageName,
                        pkgSetting.getSigningDetails(),
                        parsedSignatures,
                        SigningDetails.CertCapabilities.INSTALLED_DATA)
                                || matchSignaturesRecover(
                                        packageName,
                                        parsedSignatures,
                                        pkgSetting.getSigningDetails(),
                                        SigningDetails.CertCapabilities.ROLLBACK);
            }

            if (!match && isRollback) {
                // Since a rollback can only be initiated for an APK previously installed on the
                // device allow rolling back to a previous signing key even if the rollback
                // capability has not been granted.
                match = pkgSetting.getSigningDetails().hasAncestorOrSelf(parsedSignatures);
            }

            if (!match) {
                throw new PackageManagerException(INSTALL_FAILED_UPDATE_INCOMPATIBLE,
                        "Existing package " + packageName
                                + " signatures do not match newer version; ignoring!");
            }
        }
        // Check for shared user signatures
        if (sharedUserSetting != null
                && sharedUserSetting.getSigningDetails() != SigningDetails.UNKNOWN) {
            // Already existing package. Make sure signatures match.  In case of signing certificate
            // rotation, the packages with newer certs need to be ok with being sharedUserId with
            // the older ones.  We check to see if either the new package is signed by an older cert
            // with which the current sharedUser is ok, or if it is signed by a newer one, and is ok
            // with being sharedUser with the existing signing cert.
            boolean match = canJoinSharedUserId(packageName, parsedSignatures, sharedUserSetting,
                    pkgSetting.getSigningDetails().getSignatures() != null
                            ? SHARED_USER_ID_JOIN_TYPE_UPDATE : SHARED_USER_ID_JOIN_TYPE_INSTALL);
            if (!match && compareCompat) {
                match = matchSignaturesCompat(
                        packageName, sharedUserSetting.signatures, parsedSignatures);
            }
            if (!match && compareRecover) {
                match =
                        matchSignaturesRecover(packageName,
                                sharedUserSetting.signatures.mSigningDetails,
                                parsedSignatures,
                                SigningDetails.CertCapabilities.SHARED_USER_ID)
                        || matchSignaturesRecover(packageName,
                                parsedSignatures,
                                sharedUserSetting.signatures.mSigningDetails,
                                SigningDetails.CertCapabilities.SHARED_USER_ID);
                compatMatch |= match;
            }
            if (!match) {
                throw new PackageManagerException(INSTALL_FAILED_SHARED_USER_INCOMPATIBLE,
                        "Package " + packageName
                        + " has no signatures that match those in shared user "
                        + sharedUserSetting.name + "; ignoring!");
            }
            // If the lineage of this package diverges from the lineage of the sharedUserId then
            // do not allow the installation to proceed.
            if (!parsedSignatures.hasCommonAncestor(
                    sharedUserSetting.signatures.mSigningDetails)) {
                throw new PackageManagerException(INSTALL_FAILED_SHARED_USER_INCOMPATIBLE,
                        "Package " + packageName + " has a signing lineage "
                                + "that diverges from the lineage of the sharedUserId");
            }
        }
        return compatMatch;
    }

    /**
     * Returns whether the package {@code packageName} can join the sharedUserId based on the
     * settings in {@code sharedUserSetting}.
     * <p>
     * A sharedUserId maintains a shared {@link SigningDetails} containing the full lineage and
     * capabilities for each package in the sharedUserId. A package can join the sharedUserId if
     * its current signer is the same as the shared signer, or if the current signer of either
     * is in the signing lineage of the other with the {@link
     * SigningDetails.CertCapabilities#SHARED_USER_ID} capability granted to that previous signer
     * in the lineage. In the case of a key compromise, an app signed with a lineage revoking
     * this capability from a previous signing key can still join the sharedUserId with another
     * app signed with this previous key if the joining app is being updated; however, a new
     * install will not be allowed until all apps have rotated off the key with the capability
     * revoked.
     *
     * @param packageName           the name of the package seeking to join the sharedUserId
     * @param packageSigningDetails the {@code SigningDetails} of the package seeking to join the
     *                              sharedUserId
     * @param sharedUserSetting     the {@code SharedUserSetting} for the sharedUserId {@code
     *                              packageName} is seeking to join
     * @param joinType              the type of join (install, update, system, etc)
     * @return true if the package seeking to join the sharedUserId meets the requirements
     */
    public static boolean canJoinSharedUserId(@NonNull String packageName,
            @NonNull SigningDetails packageSigningDetails,
            @NonNull SharedUserSetting sharedUserSetting, @SharedUserIdJoinType int joinType) {
        SigningDetails sharedUserSigningDetails = sharedUserSetting.getSigningDetails();
        boolean capabilityGranted =
                packageSigningDetails.checkCapability(sharedUserSigningDetails, SHARED_USER_ID)
                        || sharedUserSigningDetails.checkCapability(packageSigningDetails,
                        SHARED_USER_ID);

        // If the current signer for either the package or the sharedUserId is the current signer
        // of the other or in the lineage of the other with the SHARED_USER_ID capability granted,
        // then a system and update join type can proceed; an install join type is not allowed here
        // since the sharedUserId may contain packages that are signed with a key untrusted by
        // the new package.
        if (capabilityGranted && joinType != SHARED_USER_ID_JOIN_TYPE_INSTALL) {
            return true;
        }

        // If the package is signed with a key that is no longer trusted by the sharedUserId, then
        // the join should not be allowed unless this is a system join type; system packages can
        // join the sharedUserId as long as they share a common lineage.
        if (!capabilityGranted && sharedUserSigningDetails.hasAncestor(packageSigningDetails)) {
            if (joinType == SHARED_USER_ID_JOIN_TYPE_SYSTEM) {
                return true;
            }
            return false;
        }

        // If the package is signed with a rotated key that no longer trusts the sharedUserId key,
        // then allow system and update join types to rotate away from an untrusted key; install
        // join types are not allowed since a new package that doesn't trust a previous key
        // shouldn't be allowed to join until all packages in the sharedUserId have rotated off the
        // untrusted key.
        if (!capabilityGranted && packageSigningDetails.hasAncestor(sharedUserSigningDetails)) {
            if (joinType != SHARED_USER_ID_JOIN_TYPE_INSTALL) {
                return true;
            }
            return false;
        }

        // If the capability is not granted and the package signatures are not an ancestor
        // or descendant of the sharedUserId signatures, then do not allow any join type to join
        // the sharedUserId since there are no common signatures.
        if (!capabilityGranted) {
            return false;
        }

        // At this point this is a new install with the capability granted; ensure the current
        // packages in the sharedUserId are all signed by a key trusted by the new package.
        final ArraySet<PackageStateInternal> susPackageStates =
                (ArraySet<PackageStateInternal>) sharedUserSetting.getPackageStates();
        if (packageSigningDetails.hasPastSigningCertificates()) {
            for (PackageStateInternal shUidPkgSetting : susPackageStates) {
                SigningDetails shUidSigningDetails = shUidPkgSetting.getSigningDetails();
                // The capability check only needs to be performed against the package if it is
                // signed with a key that is in the lineage of the package being installed.
                if (packageSigningDetails.hasAncestor(shUidSigningDetails)) {
                    if (!packageSigningDetails.checkCapability(shUidSigningDetails,
                            SigningDetails.CertCapabilities.SHARED_USER_ID)) {
                        Slog.d(TAG, "Package " + packageName
                                + " revoked the sharedUserId capability from the"
                                + " signing key used to sign "
                                + shUidPkgSetting.getPackageName());
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Extract native libraries to a target path
     */
    public static int extractNativeBinaries(File dstCodePath, String packageName) {
        final File libraryRoot = new File(dstCodePath, LIB_DIR_NAME);
        NativeLibraryHelper.Handle handle = null;
        try {
            handle = NativeLibraryHelper.Handle.create(dstCodePath);
            return NativeLibraryHelper.copyNativeBinariesWithOverride(handle, libraryRoot,
                    null /*abiOverride*/, false /*isIncremental*/);
        } catch (IOException e) {
            logCriticalInfo(Log.ERROR, "Failed to extract native libraries"
                    + "; pkg: " + packageName);
            return PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
        } finally {
            IoUtils.closeQuietly(handle);
        }
    }

    /**
     * Remove native libraries of a given package
     */
    public static void removeNativeBinariesLI(PackageSetting ps) {
        if (ps != null) {
            NativeLibraryHelper.removeNativeBinariesLI(ps.getLegacyNativeLibraryPath());
        }
    }

    /**
     * Wait for native library extraction to be done in IncrementalService
     */
    public static void waitForNativeBinariesExtractionForIncremental(
            ArraySet<IncrementalStorage> incrementalStorages) {
        if (incrementalStorages.isEmpty()) {
            return;
        }
        try {
            // Native library extraction may take very long time: each page could potentially
            // wait for either 10s or 100ms (adb vs non-adb data loader), and that easily adds
            // up to a full watchdog timeout of 1 min, killing the system after that. It doesn't
            // make much sense as blocking here doesn't lock up the framework, but only blocks
            // the installation session and the following ones.
            Watchdog.getInstance().pauseWatchingCurrentThread("native_lib_extract");
            for (int i = 0; i < incrementalStorages.size(); ++i) {
                IncrementalStorage storage = incrementalStorages.valueAtUnchecked(i);
                storage.waitForNativeBinariesExtraction();
            }
        } finally {
            Watchdog.getInstance().resumeWatchingCurrentThread("native_lib_extract");
        }
    }

    /**
     * Decompress files stored in codePath to dstCodePath for a certain package.
     */
    public static int decompressFiles(String codePath, File dstCodePath, String packageName) {
        final File[] compressedFiles = getCompressedFiles(codePath);
        int ret = PackageManager.INSTALL_SUCCEEDED;
        try {
            makeDirRecursive(dstCodePath, 0755);
            for (File srcFile : compressedFiles) {
                final String srcFileName = srcFile.getName();
                final String dstFileName = srcFileName.substring(
                        0, srcFileName.length() - COMPRESSED_EXTENSION.length());
                final File dstFile = new File(dstCodePath, dstFileName);
                ret = decompressFile(srcFile, dstFile);
                if (ret != PackageManager.INSTALL_SUCCEEDED) {
                    logCriticalInfo(Log.ERROR, "Failed to decompress"
                            + "; pkg: " + packageName
                            + ", file: " + dstFileName);
                    break;
                }
            }
        } catch (ErrnoException e) {
            logCriticalInfo(Log.ERROR, "Failed to decompress"
                    + "; pkg: " + packageName
                    + ", err: " + e.errno);
        }
        return ret;
    }

    public static int decompressFile(File srcFile, File dstFile) throws ErrnoException {
        if (DEBUG_COMPRESSION) {
            Slog.i(TAG, "Decompress file"
                    + "; src: " + srcFile.getAbsolutePath()
                    + ", dst: " + dstFile.getAbsolutePath());
        }
        final AtomicFile atomicFile = new AtomicFile(dstFile);
        FileOutputStream outputStream = null;
        try (
                InputStream fileIn = new GZIPInputStream(new FileInputStream(srcFile))
        ) {
            outputStream = atomicFile.startWrite();
            FileUtils.copy(fileIn, outputStream);
            // Flush anything in buffer before chmod, because any writes after chmod will fail.
            outputStream.flush();
            Os.fchmod(outputStream.getFD(), DEFAULT_FILE_ACCESS_MODE);
            atomicFile.finishWrite(outputStream);
            return PackageManager.INSTALL_SUCCEEDED;
        } catch (IOException e) {
            logCriticalInfo(Log.ERROR, "Failed to decompress file"
                    + "; src: " + srcFile.getAbsolutePath()
                    + ", dst: " + dstFile.getAbsolutePath());
            atomicFile.failWrite(outputStream);
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
            ret.recommendedInstallLocation = InstallLocationUtils.RECOMMEND_FAILED_INVALID_APK;
            return ret;
        }

        final File packageFile = new File(packagePath);
        final long sizeBytes;
        if (!PackageInstallerSession.isArchivedInstallation(flags)) {
            try {
                sizeBytes = InstallLocationUtils.calculateInstalledSize(pkg, abiOverride);
            } catch (IOException e) {
                if (!packageFile.exists()) {
                    ret.recommendedInstallLocation =
                            InstallLocationUtils.RECOMMEND_FAILED_INVALID_URI;
                } else {
                    ret.recommendedInstallLocation =
                            InstallLocationUtils.RECOMMEND_FAILED_INVALID_APK;
                }

                return ret;
            }
        } else {
            sizeBytes = 0;
        }

        final PackageInstaller.SessionParams sessionParams = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_INVALID);
        sessionParams.appPackageName = pkg.getPackageName();
        sessionParams.installLocation = pkg.getInstallLocation();
        sessionParams.sizeBytes = sizeBytes;
        sessionParams.installFlags = flags;
        final int recommendedInstallLocation;
        try {
            recommendedInstallLocation = InstallLocationUtils.resolveInstallLocation(context,
                    sessionParams);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
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
        ret.isSdkLibrary = pkg.isIsSdkLibrary();

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
            return InstallLocationUtils.calculateInstalledSize(result.getResult(), abiOverride);
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
                O_RDWR | O_CREAT, DEFAULT_FILE_ACCESS_MODE);
        Os.chmod(targetFile.getAbsolutePath(), DEFAULT_FILE_ACCESS_MODE);
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
            return ApkChecksums.verityHashForFile(new File(filename), hashInfo.rawRootHash);
        } catch (IOException e) {
            Slog.i(TAG, "Could not obtain verity root hash", e);
        }
        return null;
    }

    public static boolean isSystemApp(PackageStateInternal ps) {
        return (ps.getFlags() & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    public static boolean isUpdatedSystemApp(PackageStateInternal ps) {
        return (ps.getFlags() & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
    }

    private static ParsedMainComponent componentInfoToComponent(
            ComponentInfo info, ComponentResolverApi resolver, boolean isReceiver) {
        if (info instanceof ActivityInfo) {
            if (isReceiver) {
                return resolver.getReceiver(info.getComponentName());
            } else {
                return resolver.getActivity(info.getComponentName());
            }
        } else if (info instanceof ServiceInfo) {
            return resolver.getService(info.getComponentName());
        } else {
            // This shall never happen
            throw new IllegalArgumentException("Unsupported component type");
        }
    }

    /**
     * Under the correct conditions, remove components if the intent has null action.
     *
     * `compat` and `snapshot` may be null when this method is called in ActivityManagerService
     * CTS tests. The code in this method will properly avoid control flows using these arguments.
     */
    public static void applyNullActionBlocking(
            @Nullable PlatformCompat compat, @Nullable PackageDataSnapshot snapshot,
            List componentList, boolean isReceiver, Intent intent, int filterCallingUid) {
        if (ActivityManager.canAccessUnexportedComponents(filterCallingUid)) return;

        final Computer computer = (Computer) snapshot;
        ComponentResolverApi resolver = null;

        final boolean enforce = android.security.Flags.blockNullActionIntents()
                && (compat == null || compat.isChangeEnabledByUidInternal(
                        IntentFilter.BLOCK_NULL_ACTION_INTENTS, filterCallingUid));

        for (int i = componentList.size() - 1; i >= 0; --i) {
            boolean match = true;

            Object c = componentList.get(i);
            if (c instanceof ResolveInfo resolveInfo) {
                if (computer == null) {
                    // PackageManagerService is not started
                    return;
                }
                if (resolver == null) {
                    resolver = computer.getComponentResolver();
                }
                final ParsedMainComponent comp = componentInfoToComponent(
                        resolveInfo.getComponentInfo(), resolver, isReceiver);
                if (!comp.getIntents().isEmpty() && intent.getAction() == null) {
                    match = false;
                }
            } else if (c instanceof IntentFilter) {
                if (intent.getAction() == null) {
                    match = false;
                }
            }

            if (!match) {
                ActivityManagerUtils.logUnsafeIntentEvent(
                        UNSAFE_INTENT_EVENT_REPORTED__EVENT_TYPE__NULL_ACTION_MATCH,
                        filterCallingUid, intent, null, enforce);
                if (enforce) {
                    Slog.w(TAG, "Blocking intent with null action: " + intent);
                    componentList.remove(i);
                }
            }
        }
    }

    public static void applyEnforceIntentFilterMatching(
            PlatformCompat compat, PackageDataSnapshot snapshot,
            List<ResolveInfo> resolveInfos, boolean isReceiver,
            Intent intent, String resolvedType, int filterCallingUid) {
        if (DISABLE_ENFORCE_INTENTS_TO_MATCH_INTENT_FILTERS.get()) return;

        // Do not enforce filter matching when the caller is system or root
        if (ActivityManager.canAccessUnexportedComponents(filterCallingUid)) return;

        final Computer computer = (Computer) snapshot;
        final ComponentResolverApi resolver = computer.getComponentResolver();

        final Printer logPrinter = DEBUG_INTENT_MATCHING
                ? new LogPrinter(Log.VERBOSE, TAG, Log.LOG_ID_SYSTEM)
                : null;

        final boolean enforceMatch = android.security.Flags.enforceIntentFilterMatch()
                && compat.isChangeEnabledByUidInternal(
                        ENFORCE_INTENTS_TO_MATCH_INTENT_FILTERS, filterCallingUid);
        final boolean blockNullAction = android.security.Flags.blockNullActionIntents()
                && compat.isChangeEnabledByUidInternal(
                        IntentFilter.BLOCK_NULL_ACTION_INTENTS, filterCallingUid);

        for (int i = resolveInfos.size() - 1; i >= 0; --i) {
            final ComponentInfo info = resolveInfos.get(i).getComponentInfo();

            // Skip filter matching when the caller is targeting the same app
            if (UserHandle.isSameApp(filterCallingUid, info.applicationInfo.uid)) {
                continue;
            }

            final ParsedMainComponent comp = componentInfoToComponent(info, resolver, isReceiver);

            if (comp == null || comp.getIntents().isEmpty()) {
                continue;
            }

            Boolean match = null;

            if (intent.getAction() == null) {
                ActivityManagerUtils.logUnsafeIntentEvent(
                        UNSAFE_INTENT_EVENT_REPORTED__EVENT_TYPE__NULL_ACTION_MATCH,
                        filterCallingUid, intent, resolvedType, enforceMatch && blockNullAction);
                if (blockNullAction) {
                    // Skip intent filter matching if blocking null action
                    match = false;
                }
            }

            if (match == null) {
                // Check if any intent filter matches
                for (int j = 0, size = comp.getIntents().size(); j < size; ++j) {
                    IntentFilter intentFilter = comp.getIntents().get(j).getIntentFilter();
                    if (IntentResolver.intentMatchesFilter(intentFilter, intent, resolvedType)) {
                        match = true;
                        break;
                    }
                }
            }

            // At this point, the value `match` has the following states:
            // null : Intent does not match any intent filter
            // false: Null action intent detected AND blockNullAction == true
            // true : The intent matches at least one intent filter

            if (match == null) {
                ActivityManagerUtils.logUnsafeIntentEvent(
                        UNSAFE_INTENT_EVENT_REPORTED__EVENT_TYPE__EXPLICIT_INTENT_FILTER_UNMATCH,
                        filterCallingUid, intent, resolvedType, enforceMatch);
                match = false;
            }

            if (!match) {
                // All non-matching intents has to be marked accordingly
                if (android.security.Flags.enforceIntentFilterMatch()) {
                    intent.addExtendedFlags(Intent.EXTENDED_FLAG_FILTER_MISMATCH);
                }
                if (enforceMatch) {
                    Slog.w(TAG, "Intent does not match component's intent filter: " + intent);
                    Slog.w(TAG, "Access blocked: " + comp.getComponentName());
                    if (DEBUG_INTENT_MATCHING) {
                        Slog.v(TAG, "Component intent filters:");
                        comp.getIntents().forEach(f -> f.getIntentFilter().dump(logPrinter, "  "));
                        Slog.v(TAG, "-----------------------------");
                    }
                    resolveInfos.remove(i);
                }
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
            @NonNull DomainVerificationManagerInternal manager,
            @NonNull PackageStateInternal pkgSetting, @NonNull Intent intent,
            @PackageManager.ResolveInfoFlagsBits long resolveInfoFlags, @UserIdInt int userId) {
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
            String firstLevelDirName = RANDOM_DIR_PREFIX
                    + Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP);
            firstLevelDir = new File(targetDir, firstLevelDirName);
        } while (firstLevelDir.exists());

        random.nextBytes(bytes);
        String dirName = packageName + RANDOM_CODEPATH_PREFIX + Base64.encodeToString(bytes,
                Base64.URL_SAFE | Base64.NO_WRAP);
        final File result = new File(firstLevelDir, dirName);
        if (DEBUG && !Objects.equals(tryParsePackageName(result.getName()), packageName)) {
            throw new RuntimeException(
                    "codepath is off: " + result.getName() + " (" + packageName + ")");
        }
        return result;
    }

    static String tryParsePackageName(@NonNull String codePath) throws IllegalArgumentException {
        int packageNameEnds = codePath.indexOf(RANDOM_CODEPATH_PREFIX);
        if (packageNameEnds == -1) {
            throw new IllegalArgumentException("Not a valid package folder name");
        }
        return codePath.substring(0, packageNameEnds);
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

    /**
     * Enforces that only the system UID or root's UID or shell's UID can call
     * a method exposed via Binder.
     *
     * @param message used as message if SecurityException is thrown
     * @throws SecurityException if the caller is not system or shell
     */
    public static void enforceSystemOrRootOrShell(String message) {
        if (!isSystemOrRootOrShell()) {
            throw new SecurityException(message);
        }
    }

    /**
     * Check if the Binder caller is system UID, root's UID, or shell's UID.
     */
    public static boolean isSystemOrRootOrShell() {
        return isSystemOrRootOrShell(Binder.getCallingUid());
    }

    /**
     * @see #isSystemOrRoot()
     */
    public static boolean isSystemOrRootOrShell(int uid) {
        return uid == Process.SYSTEM_UID || uid == Process.ROOT_UID || uid == Process.SHELL_UID;
    }

    /**
     * Check if the Binder caller is system UID or root's UID.
     */
    public static boolean isSystemOrRoot() {
        final int uid = Binder.getCallingUid();
        return isSystemOrRoot(uid);
    }

    /**
     * Check if a UID is system UID or root's UID.
     */
    public static boolean isSystemOrRoot(int uid) {
        return uid == Process.SYSTEM_UID || uid == Process.ROOT_UID;
    }

    /**
     * Check if a UID is non-system UID adopted shell permission.
     */
    public static boolean isAdoptedShell(int uid, Context context) {
        return uid != Process.SYSTEM_UID && context.checkCallingOrSelfPermission(
                Manifest.permission.USE_SYSTEM_DATA_LOADERS) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Check if a UID is system UID or shell's UID.
     */
    public static boolean isRootOrShell(int uid) {
        return uid == Process.ROOT_UID || uid == Process.SHELL_UID;
    }

    /**
     * Enforces that only the system UID or root's UID can call a method exposed
     * via Binder.
     *
     * @param message used as message if SecurityException is thrown
     * @throws SecurityException if the caller is not system or root
     */
    public static void enforceSystemOrRoot(String message) {
        if (!isSystemOrRoot()) {
            throw new SecurityException(message);
        }
    }

    public static @Nullable File preparePackageParserCache(boolean forEngBuild,
            boolean isUserDebugBuild, String incrementalVersion) {
        if (!FORCE_PACKAGE_PARSED_CACHE_ENABLED) {
            if (!DEFAULT_PACKAGE_PARSER_CACHE_ENABLED) {
                return null;
            }

            // Disable package parsing on eng builds to allow for faster incremental development.
            if (forEngBuild) {
                return null;
            }

            if (SystemProperties.getBoolean("pm.boot.disable_package_cache", false)) {
                Slog.i(TAG, "Disabling package parser cache due to system property.");
                return null;
            }
        }

        // The base directory for the package parser cache lives under /data/system/.
        final File cacheBaseDir = Environment.getPackageCacheDirectory();
        if (!FileUtils.createDir(cacheBaseDir)) {
            return null;
        }

        // There are several items that need to be combined together to safely
        // identify cached items. In particular, changing the value of certain
        // feature flags should cause us to invalidate any caches.
        final String cacheName = FORCE_PACKAGE_PARSED_CACHE_ENABLED ? "debug"
                : PackagePartitions.FINGERPRINT;

        // Reconcile cache directories, keeping only what we'd actually use.
        for (File cacheDir : FileUtils.listFilesOrEmpty(cacheBaseDir)) {
            if (Objects.equals(cacheName, cacheDir.getName())) {
                Slog.d(TAG, "Keeping known cache " + cacheDir.getName());
            } else {
                Slog.d(TAG, "Destroying unknown cache " + cacheDir.getName());
                FileUtils.deleteContentsAndDir(cacheDir);
            }
        }

        // Return the versioned package cache directory.
        File cacheDir = FileUtils.createDir(cacheBaseDir, cacheName);

        if (cacheDir == null) {
            // Something went wrong. Attempt to delete everything and return.
            Slog.wtf(TAG, "Cache directory cannot be created - wiping base dir " + cacheBaseDir);
            FileUtils.deleteContentsAndDir(cacheBaseDir);
            return null;
        }

        // The following is a workaround to aid development on non-numbered userdebug
        // builds or cases where "adb sync" is used on userdebug builds. If we detect that
        // the system partition is newer.
        //
        // NOTE: When no BUILD_NUMBER is set by the build system, it defaults to a build
        // that starts with "eng." to signify that this is an engineering build and not
        // destined for release.
        if (isUserDebugBuild && incrementalVersion.startsWith("eng.")) {
            Slog.w(TAG, "Wiping cache directory because the system partition changed.");

            // Heuristic: If the /system directory has been modified recently due to an "adb sync"
            // or a regular make, then blow away the cache. Note that mtimes are *NOT* reliable
            // in general and should not be used for production changes. In this specific case,
            // we know that they will work.
            File frameworkDir =
                    new File(Environment.getRootDirectory(), "framework");
            if (cacheDir.lastModified() < frameworkDir.lastModified()) {
                FileUtils.deleteContents(cacheBaseDir);
                cacheDir = FileUtils.createDir(cacheBaseDir, cacheName);
            }
        }

        return cacheDir;
    }

    /**
     * Check and throw if the given before/after packages would be considered a
     * downgrade.
     */
    public static void checkDowngrade(AndroidPackage before, PackageInfoLite after)
            throws PackageManagerException {
        if (after.getLongVersionCode() < before.getLongVersionCode()) {
            throw new PackageManagerException(INSTALL_FAILED_VERSION_DOWNGRADE,
                    "Update version code " + after.versionCode + " is older than current "
                            + before.getLongVersionCode());
        } else if (after.getLongVersionCode() == before.getLongVersionCode()) {
            if (after.baseRevisionCode < before.getBaseRevisionCode()) {
                throw new PackageManagerException(INSTALL_FAILED_VERSION_DOWNGRADE,
                        "Update base revision code " + after.baseRevisionCode
                                + " is older than current " + before.getBaseRevisionCode());
            }

            if (!ArrayUtils.isEmpty(after.splitNames)) {
                for (int i = 0; i < after.splitNames.length; i++) {
                    final String splitName = after.splitNames[i];
                    final int j = ArrayUtils.indexOf(before.getSplitNames(), splitName);
                    if (j != -1) {
                        if (after.splitRevisionCodes[i] < before.getSplitRevisionCodes()[j]) {
                            throw new PackageManagerException(INSTALL_FAILED_VERSION_DOWNGRADE,
                                    "Update split " + splitName + " revision code "
                                            + after.splitRevisionCodes[i]
                                            + " is older than current "
                                            + before.getSplitRevisionCodes()[j]);
                        }
                    }
                }
            }
        }
    }

    /**
     * Check if package name is com.android.shell or is null.
     */
    public static boolean isInstalledByAdb(String initiatingPackageName) {
        return initiatingPackageName == null || SHELL_PACKAGE_NAME.equals(initiatingPackageName);
    }

    /**
     * Extract the app.metadata file from apk.
     */
    public static boolean extractAppMetadataFromApk(AndroidPackage pkg,
            String appMetadataFilePath, boolean isSystem) {
        if (appMetadataFilePath == null) {
            return false;
        }
        File appMetadataFile = new File(appMetadataFilePath);
        if (appMetadataFile.exists()) {
            return true;
        }
        Map<String, Property> properties = pkg.getProperties();
        if (!properties.containsKey(PROPERTY_ANDROID_SAFETY_LABEL_PATH)) {
            return false;
        }
        Property fileInAPkPathProperty = properties.get(PROPERTY_ANDROID_SAFETY_LABEL_PATH);
        if (!fileInAPkPathProperty.isString()) {
            return false;
        }
        if (isSystem && !appMetadataFile.getParentFile().exists()) {
            try {
                makeDirRecursive(appMetadataFile.getParentFile(), 0700);
            } catch (Exception e) {
                Slog.e(TAG, "Failed to create app metadata dir for package "
                        + pkg.getPackageName() + ": " + e.getMessage());
                return false;
            }
        }
        String fileInApkPath = fileInAPkPathProperty.getString();
        List<AndroidPackageSplit> splits = pkg.getSplits();
        for (int i = 0; i < splits.size(); i++) {
            try (ZipFile zipFile = new ZipFile(splits.get(i).getPath())) {
                ZipEntry zipEntry = zipFile.getEntry(fileInApkPath);
                if (zipEntry != null
                        && (isSystem || zipEntry.getSize() <= getAppMetadataSizeLimit())) {
                    try (InputStream in = zipFile.getInputStream(zipEntry)) {
                        try (FileOutputStream out = new FileOutputStream(appMetadataFile)) {
                            FileUtils.copy(in, out);
                            Os.chmod(appMetadataFile.getAbsolutePath(),
                                    APP_METADATA_FILE_ACCESS_MODE);
                            return true;
                        }
                    }
                }
            } catch (Exception e) {
                Slog.e(TAG, e.getMessage());
                appMetadataFile.delete();
            }
        }
        return false;
    }

    public static void linkFilesToOldDirs(@NonNull Installer installer,
                                           @NonNull String packageName,
                                           @NonNull File newPath,
                                           @Nullable Set<File> oldPaths) {
        if (oldPaths == null || oldPaths.isEmpty()) {
            return;
        }
        if (IncrementalManager.isIncrementalPath(newPath.getPath())) {
            //TODO(b/291212866): handle incremental installs
            return;
        }
        final File[] filesInNewPath = newPath.listFiles();
        if (filesInNewPath == null || filesInNewPath.length == 0) {
            return;
        }
        final List<File> splitApks = new ArrayList<>();
        for (File file : filesInNewPath) {
            if (!file.isDirectory() && file.toString().endsWith(".apk")) {
                splitApks.add(file);
            }
        }
        if (splitApks.isEmpty()) {
            return;
        }
        final File[] splitApkNames = splitApks.toArray(new File[0]);
        for (File oldPath : oldPaths) {
            if (!oldPath.exists()) {
                continue;
            }
            linkFilesAndSetModes(installer, packageName, newPath, oldPath, splitApkNames,
                    DEFAULT_FILE_ACCESS_MODE);
            linkNativeLibraries(installer, packageName, newPath, oldPath, LIB_DIR_NAME);
            linkNativeLibraries(installer, packageName, newPath, oldPath, LIB64_DIR_NAME);
        }

    }

    private static void linkNativeLibraries(@NonNull Installer installer,
                                            @NonNull String packageName,
                                            @NonNull File sourcePath, @NonNull File targetPath,
                                            @NonNull String libDirName) {
        final File sourceLibDir = new File(sourcePath, libDirName);
        if (!sourceLibDir.exists()) {
            return;
        }
        final File targetLibDir = new File(targetPath, libDirName);
        if (!targetLibDir.exists()) {
            try {
                NativeLibraryHelper.createNativeLibrarySubdir(targetLibDir);
            } catch (IOException e) {
                Slog.w(PackageManagerService.TAG, "Failed to create native library dir at <"
                        + targetLibDir + ">", e);
                return;
            }
        }
        final File[] archs = sourceLibDir.listFiles();
        if (archs == null) {
            return;
        }
        for (File arch : archs) {
            final File targetArchDir = new File(targetLibDir, arch.getName());
            if (!targetArchDir.exists()) {
                try {
                    NativeLibraryHelper.createNativeLibrarySubdir(targetArchDir);
                } catch (IOException e) {
                    Slog.w(PackageManagerService.TAG, "Failed to create native library subdir at <"
                            + targetArchDir + ">", e);
                    continue;
                }
            }
            final File sourceArchDir = new File(sourceLibDir, arch.getName());
            final File[] files = sourceArchDir.listFiles();
            if (files == null || files.length == 0) {
                continue;
            }
            linkFilesAndSetModes(installer, packageName, sourceArchDir, targetArchDir, files,
                    DEFAULT_NATIVE_LIBRARY_FILE_ACCESS_MODE);
        }
    }

    // Link the files with specified names from under the sourcePath to be under the targetPath
    private static void linkFilesAndSetModes(@NonNull Installer installer, String packageName,
            @NonNull File sourcePath, @NonNull File targetPath, @NonNull File[] files, int mode) {
        for (File file : files) {
            final String fileName = file.getName();
            final File sourceFile = new File(sourcePath, fileName);
            final File targetFile = new File(targetPath, fileName);
            if (targetFile.exists()) {
                if (DEBUG) {
                    Slog.d(PackageManagerService.TAG, "Skipping existing linked file <"
                            + targetFile + ">");
                }
                continue;
            }
            try {
                installer.linkFile(packageName, fileName,
                        sourcePath.getAbsolutePath(), targetPath.getAbsolutePath());
                if (DEBUG) {
                    Slog.d(PackageManagerService.TAG, "Linked <"
                            + sourceFile + "> to <" + targetFile + ">");
                }
            } catch (Installer.InstallerException e) {
                Slog.w(PackageManagerService.TAG, "Failed to link native library <"
                        + sourceFile + "> to <" + targetFile + ">", e);
                continue;
            }
            try {
                Os.chmod(targetFile.getAbsolutePath(), mode);
            } catch (ErrnoException e) {
                Slog.w(PackageManagerService.TAG, "Failed to set mode for linked file <"
                        + targetFile + ">", e);
                continue;
            }
            if (!SELinux.restorecon(targetFile)) {
                Slog.w(PackageManagerService.TAG, "Failed to restorecon for linked file <"
                        + targetFile + ">");
            }
        }
    }
}
