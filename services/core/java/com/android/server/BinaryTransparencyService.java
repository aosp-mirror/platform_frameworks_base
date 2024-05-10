/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.apex.ApexInfo;
import android.apex.IApexService;
import android.app.compat.CompatChanges;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.compat.annotation.ChangeId;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApexStagedEvent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IBackgroundInstallControlService;
import android.content.pm.IPackageManagerNative;
import android.content.pm.IStagedApexObserver;
import android.content.pm.InstallSourceInfo;
import android.content.pm.ModuleInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.content.pm.SigningInfo;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.hardware.biometrics.SensorProperties;
import android.hardware.biometrics.SensorProperties.ComponentInfo;
import android.hardware.face.FaceManager;
import android.hardware.face.FaceSensorProperties;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.hardware.face.IFaceAuthenticatorsRegisteredCallback;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorProperties;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IFingerprintAuthenticatorsRegisteredCallback;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.util.PackageUtils;
import android.util.Slog;
import android.util.apk.ApkSignatureVerifier;
import android.util.apk.ApkSigningBlockUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.IBinaryTransparencyService;
import com.android.internal.util.FrameworkStatsLog;
import com.android.modules.expresslog.Histogram;
import com.android.server.pm.ApexManager;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.AndroidPackageSplit;
import com.android.server.pm.pkg.PackageState;

import libcore.util.HexEncoding;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * @hide
 */
public class BinaryTransparencyService extends SystemService {
    private static final String TAG = "TransparencyService";

    @VisibleForTesting
    static final String VBMETA_DIGEST_UNINITIALIZED = "vbmeta-digest-uninitialized";
    @VisibleForTesting
    static final String VBMETA_DIGEST_UNAVAILABLE = "vbmeta-digest-unavailable";
    @VisibleForTesting
    static final String SYSPROP_NAME_VBETA_DIGEST = "ro.boot.vbmeta.digest";

    @VisibleForTesting
    static final String BINARY_HASH_ERROR = "SHA256HashError";

    static final long RECORD_MEASUREMENTS_COOLDOWN_MS = 24 * 60 * 60 * 1000;

    static final String APEX_PRELOAD_LOCATION_ERROR = "could-not-be-determined";

    // Copy from the atom. Consistent for both ApexInfoGathered and MobileBundledAppInfoGathered.
    static final int DIGEST_ALGORITHM_UNKNOWN = 0;
    static final int DIGEST_ALGORITHM_CHUNKED_SHA256 = 1;
    static final int DIGEST_ALGORITHM_CHUNKED_SHA512 = 2;
    static final int DIGEST_ALGORITHM_VERITY_CHUNKED_SHA256 = 3;
    static final int DIGEST_ALGORITHM_SHA256 = 4;

    // used for indicating any type of error during MBA measurement
    static final int MBA_STATUS_ERROR = 0;
    // used for indicating factory condition preloads
    static final int MBA_STATUS_PRELOADED = 1;
    // used for indicating preloaded apps that are updated
    static final int MBA_STATUS_UPDATED_PRELOAD = 2;
    // used for indicating newly installed MBAs
    static final int MBA_STATUS_NEW_INSTALL = 3;
    // used for indicating newly installed MBAs that are updated (but unused currently)
    static final int MBA_STATUS_UPDATED_NEW_INSTALL = 4;

    @VisibleForTesting
    static final String KEY_ENABLE_BIOMETRIC_PROPERTY_VERIFICATION =
            "enable_biometric_property_verification";

    private static final boolean DEBUG = false;     // toggle this for local debug

    private static final Histogram digestAllPackagesLatency = new Histogram(
            "binary_transparency.value_digest_all_packages_latency_uniform",
            new Histogram.UniformOptions(50, 0, 500));

    private final Context mContext;
    private String mVbmetaDigest;
    // the system time (in ms) the last measurement was taken
    private long mMeasurementsLastRecordedMs;
    private PackageManagerInternal mPackageManagerInternal;
    private BiometricLogger mBiometricLogger;

    /**
     * Guards whether or not measurements of MBA to be performed. When this change is enabled,
     * measurements of MBAs are performed. But when it is disabled, only measurements of APEX
     * and modules are done.
     */
    @ChangeId
    public static final long LOG_MBA_INFO = 245692487L;

    final class BinaryTransparencyServiceImpl extends IBinaryTransparencyService.Stub {

        @Override
        public String getSignedImageInfo() {
            return mVbmetaDigest;
        }

        /**
         * A helper function to compute the SHA256 digest of APK package signer.
         * @param signingInfo The signingInfo of a package, usually {@link PackageInfo#signingInfo}.
         * @return an array of {@code String} representing hex encoded string of the
         *         SHA256 digest of APK signer(s). The number of signers will be reflected by the
         *         size of the array.
         *         However, {@code null} is returned if there is any error.
         */
        private String[] computePackageSignerSha256Digests(@Nullable SigningInfo signingInfo) {
            if (signingInfo == null) {
                Slog.e(TAG, "signingInfo is null");
                return null;
            }

            Signature[] packageSigners = signingInfo.getApkContentsSigners();
            List<String> resultList = new ArrayList<>();
            for (Signature packageSigner : packageSigners) {
                byte[] digest = PackageUtils.computeSha256DigestBytes(packageSigner.toByteArray());
                String digestHexString = HexEncoding.encodeToString(digest, false);
                resultList.add(digestHexString);
            }
            return resultList.toArray(new String[1]);
        }

        /*
         * Perform basic measurement (i.e. content digest) on a given app, including the split APKs.
         *
         * @param packageState The package to be measured.
         * @param mbaStatus Assign this value of MBA status to the returned elements.
         * @return a @{@code List<IBinaryTransparencyService.AppInfo>}
         */
        private @NonNull List<IBinaryTransparencyService.AppInfo> collectAppInfo(
                PackageState packageState, int mbaStatus) {
            // compute content digest
            if (DEBUG) {
                Slog.d(TAG, "Computing content digest for " + packageState.getPackageName() + " at "
                        + packageState.getPath());
            }

            var results = new ArrayList<IBinaryTransparencyService.AppInfo>();

            // Same attributes across base and splits.
            String packageName = packageState.getPackageName();
            long versionCode = packageState.getVersionCode();
            String[] signerDigests =
                    computePackageSignerSha256Digests(packageState.getSigningInfo());

            AndroidPackage pkg = packageState.getAndroidPackage();
            for (AndroidPackageSplit split : pkg.getSplits()) {
                var appInfo = new IBinaryTransparencyService.AppInfo();
                appInfo.packageName = packageName;
                appInfo.longVersion = versionCode;
                appInfo.splitName = split.getName();  // base's split name is null
                // Signer digests are consistent between splits, guaranteed by Package Manager.
                appInfo.signerDigests = signerDigests;
                appInfo.mbaStatus = mbaStatus;

                // Only digest and split name are different between splits.
                Digest digest = measureApk(split.getPath());
                appInfo.digest = digest.value();
                appInfo.digestAlgorithm = digest.algorithm();

                results.add(appInfo);
            }

            // InstallSourceInfo is only available per package name, so store it only on the base
            // APK. It's not current currently available in PackageState (there's a TODO), to we
            // need to extract manually with another call.
            //
            // Base APK is already the 0-th split from getSplits() and can't be null.
            AppInfo base = results.get(0);
            InstallSourceInfo installSourceInfo = getInstallSourceInfo(
                    packageState.getPackageName());
            if (installSourceInfo != null) {
                base.initiator = installSourceInfo.getInitiatingPackageName();
                SigningInfo initiatorSignerInfo =
                        installSourceInfo.getInitiatingPackageSigningInfo();
                if (initiatorSignerInfo != null) {
                    base.initiatorSignerDigests =
                        computePackageSignerSha256Digests(initiatorSignerInfo);
                }
                base.installer = installSourceInfo.getInstallingPackageName();
                base.originator = installSourceInfo.getOriginatingPackageName();
            }

            return results;
        }

        /**
         * Perform basic measurement (i.e. content digest) on a given APK.
         *
         * @param apkPath The APK (or APEX, since it's also an APK) file to be measured.
         * @return a {@link #Digest} with preferred digest algorithm type and the value.
         */
        private @Nullable Digest measureApk(@NonNull String apkPath) {
            // compute content digest
            Map<Integer, byte[]> contentDigests = computeApkContentDigest(apkPath);
            if (contentDigests == null) {
                Slog.d(TAG, "Failed to compute content digest for " + apkPath);
            } else {
                // in this iteration, we'll be supporting only 2 types of digests:
                // CHUNKED_SHA256 and CHUNKED_SHA512.
                // And only one of them will be available per package.
                if (contentDigests.containsKey(
                            ApkSigningBlockUtils.CONTENT_DIGEST_CHUNKED_SHA256)) {
                    return new Digest(
                            DIGEST_ALGORITHM_CHUNKED_SHA256,
                            contentDigests.get(ApkSigningBlockUtils.CONTENT_DIGEST_CHUNKED_SHA256));
                } else if (contentDigests.containsKey(
                        ApkSigningBlockUtils.CONTENT_DIGEST_CHUNKED_SHA512)) {
                    return new Digest(
                            DIGEST_ALGORITHM_CHUNKED_SHA512,
                            contentDigests.get(ApkSigningBlockUtils.CONTENT_DIGEST_CHUNKED_SHA512));
                }
            }
            // When something went wrong, fall back to simple sha256.
            byte[] digest = PackageUtils.computeSha256DigestForLargeFileAsBytes(apkPath,
                    PackageUtils.createLargeFileBuffer());
            return new Digest(DIGEST_ALGORITHM_SHA256, digest);
        }


        /**
         * Measures and records digests for *all* covered binaries/packages.
         *
         * This method will be called in a Job scheduled to take measurements periodically. If the
         * last measurement was performaned recently (less than RECORD_MEASUREMENT_COOLDOWN_MS
         * ago), the measurement and recording will be skipped.
         *
         * Packages that are covered so far are:
         * - all APEXs (introduced in Android T)
         * - all mainline modules (introduced in Android T)
         * - all preloaded apps and their update(s) (new in Android U)
         * - dynamically installed mobile bundled apps (MBAs) (new in Android U)
         */
        public void recordMeasurementsForAllPackages() {
            // check if we should measure and record
            long currentTimeMs = System.currentTimeMillis();
            if ((currentTimeMs - mMeasurementsLastRecordedMs) < RECORD_MEASUREMENTS_COOLDOWN_MS) {
                Slog.d(TAG, "Skip measurement since the last measurement was only taken at "
                        + mMeasurementsLastRecordedMs + " within the cooldown period");
                return;
            }
            Slog.d(TAG, "Measurement was last taken at " + mMeasurementsLastRecordedMs
                    + " and is now updated to: " + currentTimeMs);
            mMeasurementsLastRecordedMs = currentTimeMs;

            Bundle packagesMeasured = new Bundle();

            // measure all APEXs first
            if (DEBUG) {
                Slog.d(TAG, "Measuring APEXs...");
            }
            List<IBinaryTransparencyService.ApexInfo> allApexInfo = collectAllApexInfo(
                    /* includeTestOnly */ false);
            for (IBinaryTransparencyService.ApexInfo apexInfo : allApexInfo) {
                packagesMeasured.putBoolean(apexInfo.packageName, true);

                recordApexInfo(apexInfo);
            }
            if (DEBUG) {
                Slog.d(TAG, "Measured " + packagesMeasured.size()
                        + " packages after considering APEXs.");
            }

            // proceed with all preloaded apps
            List<IBinaryTransparencyService.AppInfo> allUpdatedPreloadInfo =
                    collectAllUpdatedPreloadInfo(packagesMeasured);
            for (IBinaryTransparencyService.AppInfo appInfo : allUpdatedPreloadInfo) {
                packagesMeasured.putBoolean(appInfo.packageName, true);
                writeAppInfoToLog(appInfo);
            }
            if (DEBUG) {
                Slog.d(TAG, "Measured " + packagesMeasured.size()
                        + " packages after considering preloads");
            }

            if (CompatChanges.isChangeEnabled(LOG_MBA_INFO)) {
                // lastly measure all newly installed MBAs
                List<IBinaryTransparencyService.AppInfo> allMbaInfo =
                        collectAllSilentInstalledMbaInfo(packagesMeasured);
                for (IBinaryTransparencyService.AppInfo appInfo : allMbaInfo) {
                    packagesMeasured.putBoolean(appInfo.packageName, true);
                    writeAppInfoToLog(appInfo);
                }
            }
            long timeSpentMeasuring = System.currentTimeMillis() - currentTimeMs;
            digestAllPackagesLatency.logSample(timeSpentMeasuring);
            if (DEBUG) {
                Slog.d(TAG, "Measured " + packagesMeasured.size()
                        + " packages altogether in " + timeSpentMeasuring + "ms");
            }
        }

        @Override
        public List<IBinaryTransparencyService.ApexInfo> collectAllApexInfo(
                boolean includeTestOnly) {
            var results = new ArrayList<IBinaryTransparencyService.ApexInfo>();
            for (PackageInfo packageInfo : getCurrentInstalledApexs()) {
                PackageState packageState = mPackageManagerInternal.getPackageStateInternal(
                        packageInfo.packageName);
                if (packageState == null) {
                    Slog.w(TAG, "Package state is unavailable, ignoring the APEX "
                            + packageInfo.packageName);
                    continue;
                }

                AndroidPackage pkg = packageState.getAndroidPackage();
                if (pkg == null) {
                    Slog.w(TAG, "Skipping the missing APK in " + pkg.getPath());
                    continue;
                }
                Digest apexChecksum = measureApk(pkg.getPath());
                if (apexChecksum == null) {
                    Slog.w(TAG, "Skipping the missing APEX in " + pkg.getPath());
                    continue;
                }

                var apexInfo = new IBinaryTransparencyService.ApexInfo();
                apexInfo.packageName = packageState.getPackageName();
                apexInfo.longVersion = packageState.getVersionCode();
                apexInfo.digest = apexChecksum.value();
                apexInfo.digestAlgorithm = apexChecksum.algorithm();
                apexInfo.signerDigests =
                        computePackageSignerSha256Digests(packageState.getSigningInfo());

                if (includeTestOnly) {
                    apexInfo.moduleName = apexPackageNameToModuleName(
                            packageState.getPackageName());
                }

                results.add(apexInfo);
            }
            return results;
        }

        @Override
        public List<IBinaryTransparencyService.AppInfo> collectAllUpdatedPreloadInfo(
                Bundle packagesToSkip) {
            final var results = new ArrayList<IBinaryTransparencyService.AppInfo>();

            PackageManager pm = mContext.getPackageManager();
            mPackageManagerInternal.forEachPackageState((packageState) -> {
                if (!packageState.isUpdatedSystemApp()) {
                    return;
                }
                if (packagesToSkip.containsKey(packageState.getPackageName())) {
                    return;
                }

                Slog.d(TAG, "Preload " + packageState.getPackageName() + " at "
                        + packageState.getPath() + " has likely been updated.");

                List<IBinaryTransparencyService.AppInfo> resultsForApp = collectAppInfo(
                        packageState, MBA_STATUS_UPDATED_PRELOAD);
                results.addAll(resultsForApp);
            });
            return results;
        }

        @Override
        public List<IBinaryTransparencyService.AppInfo> collectAllSilentInstalledMbaInfo(
                Bundle packagesToSkip) {
            var results = new ArrayList<IBinaryTransparencyService.AppInfo>();
            for (PackageInfo packageInfo : getNewlyInstalledMbas()) {
                if (packagesToSkip.containsKey(packageInfo.packageName)) {
                    continue;
                }
                PackageState packageState = mPackageManagerInternal.getPackageStateInternal(
                        packageInfo.packageName);
                if (packageState == null) {
                    Slog.w(TAG, "Package state is unavailable, ignoring the package "
                            + packageInfo.packageName);
                    continue;
                }

                List<IBinaryTransparencyService.AppInfo> resultsForApp = collectAppInfo(
                        packageState, MBA_STATUS_NEW_INSTALL);
                results.addAll(resultsForApp);
            }
            return results;
        }

        private void recordApexInfo(IBinaryTransparencyService.ApexInfo apexInfo) {
            // Must order by the proto's field number.
            FrameworkStatsLog.write(FrameworkStatsLog.APEX_INFO_GATHERED,
                    apexInfo.packageName,
                    apexInfo.longVersion,
                    (apexInfo.digest != null) ? HexEncoding.encodeToString(apexInfo.digest, false)
                            : null,
                    apexInfo.digestAlgorithm,
                    apexInfo.signerDigests);
        }

        private void writeAppInfoToLog(IBinaryTransparencyService.AppInfo appInfo) {
            // Must order by the proto's field number.
            FrameworkStatsLog.write(FrameworkStatsLog.MOBILE_BUNDLED_APP_INFO_GATHERED,
                    appInfo.packageName,
                    appInfo.longVersion,
                    (appInfo.digest != null) ? HexEncoding.encodeToString(appInfo.digest, false)
                            : null,
                    appInfo.digestAlgorithm,
                    appInfo.signerDigests,
                    appInfo.mbaStatus,
                    appInfo.initiator,
                    appInfo.initiatorSignerDigests,
                    appInfo.installer,
                    appInfo.originator,
                    appInfo.splitName);
        }

        /**
         * A wrapper around
         * {@link ApkSignatureVerifier#verifySignaturesInternal(ParseInput, String, int, boolean)}.
         * @param pathToApk The APK's installation path
         * @return a {@code Map<Integer, byte[]>} with algorithm type as the key and content
         *         digest as the value.
         *         a {@code null} is returned upon encountering any error.
         */
        private Map<Integer, byte[]> computeApkContentDigest(String pathToApk) {
            final ParseTypeImpl input = ParseTypeImpl.forDefaultParsing();
            ParseResult<ApkSignatureVerifier.SigningDetailsWithDigests> parseResult =
                    ApkSignatureVerifier.verifySignaturesInternal(input,
                            pathToApk,
                            SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V2, false);
            if (parseResult.isError()) {
                Slog.e(TAG, "Failed to compute content digest for "
                        + pathToApk + " due to: "
                        + parseResult.getErrorMessage());
                return null;
            }
            return parseResult.getResult().contentDigests;
        }

        @Override
        public void onShellCommand(@Nullable FileDescriptor in,
                                   @Nullable FileDescriptor out,
                                   @Nullable FileDescriptor err,
                                   @NonNull String[] args,
                                   @Nullable ShellCallback callback,
                                   @NonNull ResultReceiver resultReceiver) throws RemoteException {
            (new ShellCommand() {

                private int printSignedImageInfo() {
                    final PrintWriter pw = getOutPrintWriter();
                    boolean listAllPartitions = false;
                    String opt;

                    while ((opt = getNextOption()) != null) {
                        switch (opt) {
                            case "-a":
                                listAllPartitions = true;
                                break;
                            default:
                                pw.println("ERROR: Unknown option: " + opt);
                                return 1;
                        }
                    }

                    final String signedImageInfo = getSignedImageInfo();
                    pw.println("Image Info:");
                    pw.println(Build.FINGERPRINT);
                    pw.println(signedImageInfo);
                    pw.println("");

                    if (listAllPartitions) {
                        PackageManager pm = mContext.getPackageManager();
                        if (pm == null) {
                            pw.println("ERROR: Failed to obtain an instance of package manager.");
                            return -1;
                        }

                        pw.println("Other partitions:");
                        List<Build.Partition> buildPartitions = Build.getFingerprintedPartitions();
                        for (Build.Partition buildPartition : buildPartitions) {
                            pw.println("Name: " + buildPartition.getName());
                            pw.println("Fingerprint: " + buildPartition.getFingerprint());
                            pw.println("Build time (ms): " + buildPartition.getBuildTimeMillis());
                        }
                    }
                    return 0;
                }

                private void printPackageMeasurements(PackageInfo packageInfo,
                                                      boolean useSha256,
                                                      final PrintWriter pw) {
                    Map<Integer, byte[]> contentDigests = computeApkContentDigest(
                            packageInfo.applicationInfo.sourceDir);
                    if (contentDigests == null) {
                        pw.println("ERROR: Failed to compute package content digest for "
                                + packageInfo.applicationInfo.sourceDir);
                        return;
                    }

                    if (useSha256) {
                        byte[] fileBuff = PackageUtils.createLargeFileBuffer();
                        String hexEncodedSha256Digest =
                                PackageUtils.computeSha256DigestForLargeFile(
                                        packageInfo.applicationInfo.sourceDir, fileBuff);
                        pw.print(hexEncodedSha256Digest + ",");
                    }

                    for (Map.Entry<Integer, byte[]> entry : contentDigests.entrySet()) {
                        Integer algorithmId = entry.getKey();
                        byte[] contentDigest = entry.getValue();

                        pw.print(translateContentDigestAlgorithmIdToString(algorithmId));
                        pw.print(":");
                        pw.print(HexEncoding.encodeToString(contentDigest, false));
                        pw.print("\n");
                    }
                }

                private void printPackageInstallationInfo(PackageInfo packageInfo,
                                                          boolean useSha256,
                                                          final PrintWriter pw) {
                    pw.println("--- Package Installation Info ---");
                    pw.println("Current install location: "
                            + packageInfo.applicationInfo.sourceDir);
                    if (packageInfo.applicationInfo.sourceDir.startsWith("/data/apex/")) {
                        String origPackageFilepath = getOriginalApexPreinstalledLocation(
                                packageInfo.packageName);
                        pw.println("|--> Pre-installed package install location: "
                                + origPackageFilepath);

                        if (!origPackageFilepath.equals(APEX_PRELOAD_LOCATION_ERROR)) {
                            if (useSha256) {
                                String sha256Digest = PackageUtils.computeSha256DigestForLargeFile(
                                        origPackageFilepath, PackageUtils.createLargeFileBuffer());
                                pw.println("|--> Pre-installed package SHA-256 digest: "
                                        + sha256Digest);
                            }

                            Map<Integer, byte[]> contentDigests = computeApkContentDigest(
                                    origPackageFilepath);
                            if (contentDigests == null) {
                                pw.println("|--> ERROR: Failed to compute package content digest "
                                        + "for " + origPackageFilepath);
                            } else {
                                for (Map.Entry<Integer, byte[]> entry : contentDigests.entrySet()) {
                                    Integer algorithmId = entry.getKey();
                                    byte[] contentDigest = entry.getValue();
                                    pw.println("|--> Pre-installed package content digest: "
                                            + HexEncoding.encodeToString(contentDigest, false));
                                    pw.println("|--> Pre-installed package content digest "
                                            + "algorithm: "
                                            + translateContentDigestAlgorithmIdToString(
                                                    algorithmId));
                                }
                            }
                        }
                    }
                    pw.println("First install time (ms): " + packageInfo.firstInstallTime);
                    pw.println("Last update time (ms):   " + packageInfo.lastUpdateTime);
                    // TODO(b/261493591): Determination of whether a package is preinstalled can be
                    // made more robust
                    boolean isPreloaded = (packageInfo.firstInstallTime
                            == packageInfo.lastUpdateTime);
                    pw.println("Is preloaded: " + isPreloaded);

                    InstallSourceInfo installSourceInfo = getInstallSourceInfo(
                            packageInfo.packageName);
                    if (installSourceInfo == null) {
                        pw.println("ERROR: Unable to obtain installSourceInfo of "
                                + packageInfo.packageName);
                    } else {
                        pw.println("Installation initiated by: "
                                + installSourceInfo.getInitiatingPackageName());
                        pw.println("Installation done by: "
                                + installSourceInfo.getInstallingPackageName());
                        pw.println("Installation originating from: "
                                + installSourceInfo.getOriginatingPackageName());
                    }

                    if (packageInfo.isApex) {
                        pw.println("Is an active APEX: " + packageInfo.isActiveApex);
                    }
                }

                private void printPackageSignerDetails(SigningInfo signerInfo,
                                                       final PrintWriter pw) {
                    if (signerInfo == null) {
                        pw.println("ERROR: Package's signingInfo is null.");
                        return;
                    }
                    pw.println("--- Package Signer Info ---");
                    pw.println("Has multiple signers: " + signerInfo.hasMultipleSigners());
                    pw.println("Signing key has been rotated: "
                            + signerInfo.hasPastSigningCertificates());
                    Signature[] packageSigners = signerInfo.getApkContentsSigners();
                    for (Signature packageSigner : packageSigners) {
                        byte[] packageSignerDigestBytes =
                                PackageUtils.computeSha256DigestBytes(packageSigner.toByteArray());
                        String packageSignerDigestHextring =
                                HexEncoding.encodeToString(packageSignerDigestBytes, false);
                        pw.println("Signer cert's SHA256-digest: " + packageSignerDigestHextring);
                        try {
                            PublicKey publicKey = packageSigner.getPublicKey();
                            pw.println("Signing key algorithm: " + publicKey.getAlgorithm());
                        } catch (CertificateException e) {
                            Slog.e(TAG,
                                    "Failed to obtain public key of signer for cert with hash: "
                                    + packageSignerDigestHextring, e);
                        }
                    }

                    if (!signerInfo.hasMultipleSigners()
                            && signerInfo.hasPastSigningCertificates()) {
                        pw.println("== Signing Cert Lineage (Excluding The Most Recent) ==");
                        pw.println("(Certs are sorted in the order of rotation, beginning with the "
                                   + "original signing cert)");
                        Signature[] signingCertHistory = signerInfo.getSigningCertificateHistory();
                        for (int i = 0; i < (signingCertHistory.length - 1); i++) {
                            Signature signature = signingCertHistory[i];
                            byte[] signatureDigestBytes = PackageUtils.computeSha256DigestBytes(
                                    signature.toByteArray());
                            String certHashHexString = HexEncoding.encodeToString(
                                    signatureDigestBytes, false);
                            pw.println("  ++ Signer cert #" + (i + 1) + " ++");
                            pw.println("  Cert SHA256-digest: " + certHashHexString);
                            try {
                                PublicKey publicKey = signature.getPublicKey();
                                pw.println("  Signing key algorithm: " + publicKey.getAlgorithm());
                            } catch (CertificateException e) {
                                Slog.e(TAG, "Failed to obtain public key of signer for cert "
                                        + "with hash: " + certHashHexString, e);
                            }
                        }
                    }

                }

                private void printModuleDetails(ModuleInfo moduleInfo, final PrintWriter pw) {
                    pw.println("--- Module Details ---");
                    pw.println("Module name: " + moduleInfo.getName());
                    pw.println("Module visibility: "
                            + (moduleInfo.isHidden() ? "hidden" : "visible"));
                }

                private void printAppDetails(PackageInfo packageInfo,
                                             boolean printLibraries,
                                             final PrintWriter pw) {
                    pw.println("--- App Details ---");
                    pw.println("Name: " + packageInfo.applicationInfo.name);
                    pw.println("Label: " + mContext.getPackageManager().getApplicationLabel(
                            packageInfo.applicationInfo));
                    pw.println("Description: " + packageInfo.applicationInfo.loadDescription(
                            mContext.getPackageManager()));
                    pw.println("Has code: " + packageInfo.applicationInfo.hasCode());
                    pw.println("Is enabled: " + packageInfo.applicationInfo.enabled);
                    pw.println("Is suspended: " + ((packageInfo.applicationInfo.flags
                                                    & ApplicationInfo.FLAG_SUSPENDED) != 0));

                    pw.println("Compile SDK version: " + packageInfo.compileSdkVersion);
                    pw.println("Target SDK version: "
                            + packageInfo.applicationInfo.targetSdkVersion);

                    pw.println("Is privileged: "
                            + packageInfo.applicationInfo.isPrivilegedApp());
                    pw.println("Is a stub: " + packageInfo.isStub);
                    pw.println("Is a core app: " + packageInfo.coreApp);
                    pw.println("SEInfo: " + packageInfo.applicationInfo.seInfo);
                    pw.println("Component factory: "
                            + packageInfo.applicationInfo.appComponentFactory);
                    pw.println("Process name: " + packageInfo.applicationInfo.processName);
                    pw.println("Task affinity: " + packageInfo.applicationInfo.taskAffinity);
                    pw.println("UID: " + packageInfo.applicationInfo.uid);
                    pw.println("Shared UID: " + packageInfo.sharedUserId);

                    if (printLibraries) {
                        pw.println("== App's Shared Libraries ==");
                        List<SharedLibraryInfo> sharedLibraryInfos =
                                packageInfo.applicationInfo.getSharedLibraryInfos();
                        if (sharedLibraryInfos == null || sharedLibraryInfos.isEmpty()) {
                            pw.println("<none>");
                        }

                        for (int i = 0; i < sharedLibraryInfos.size(); i++) {
                            SharedLibraryInfo sharedLibraryInfo = sharedLibraryInfos.get(i);
                            pw.println("  ++ Library #" + (i + 1) + " ++");
                            pw.println("  Lib name: " + sharedLibraryInfo.getName());
                            long libVersion = sharedLibraryInfo.getLongVersion();
                            pw.print("  Lib version: ");
                            if (libVersion == SharedLibraryInfo.VERSION_UNDEFINED) {
                                pw.print("undefined");
                            } else {
                                pw.print(libVersion);
                            }
                            pw.print("\n");

                            pw.println("  Lib package name (if available): "
                                    + sharedLibraryInfo.getPackageName());
                            pw.println("  Lib path: " + sharedLibraryInfo.getPath());
                            pw.print("  Lib type: ");
                            switch (sharedLibraryInfo.getType()) {
                                case SharedLibraryInfo.TYPE_BUILTIN:
                                    pw.print("built-in");
                                    break;
                                case SharedLibraryInfo.TYPE_DYNAMIC:
                                    pw.print("dynamic");
                                    break;
                                case SharedLibraryInfo.TYPE_STATIC:
                                    pw.print("static");
                                    break;
                                case SharedLibraryInfo.TYPE_SDK_PACKAGE:
                                    pw.print("SDK");
                                    break;
                                case SharedLibraryInfo.VERSION_UNDEFINED:
                                default:
                                    pw.print("undefined");
                                    break;
                            }
                            pw.print("\n");
                            pw.println("  Is a native lib: " + sharedLibraryInfo.isNative());
                        }
                    }

                }

                private void printHeadersHelper(@NonNull String packageType,
                                          boolean useSha256,
                                          @NonNull final PrintWriter pw) {
                    pw.print(packageType + " Info [Format: package_name,package_version,");
                    if (useSha256) {
                        pw.print("package_sha256_digest,");
                    }
                    pw.print("content_digest_algorithm:content_digest]:\n");
                }

                private int printAllApexs() {
                    final PrintWriter pw = getOutPrintWriter();
                    boolean verbose = false;
                    boolean useSha256 = false;
                    boolean printHeaders = true;
                    String opt;
                    while ((opt = getNextOption()) != null) {
                        switch (opt) {
                            case "-v":
                            case "--verbose":
                                verbose = true;
                                break;
                            case "-o":
                            case "--old":
                                useSha256 = true;
                                break;
                            case "--no-headers":
                                printHeaders = false;
                                break;
                            default:
                                pw.println("ERROR: Unknown option: " + opt);
                                return 1;
                        }
                    }

                    PackageManager pm = mContext.getPackageManager();
                    if (pm == null) {
                        pw.println("ERROR: Failed to obtain an instance of package manager.");
                        return -1;
                    }

                    if (!verbose && printHeaders) {
                        printHeadersHelper("APEX", useSha256, pw);
                    }
                    for (PackageInfo packageInfo : getCurrentInstalledApexs()) {
                        if (verbose && printHeaders) {
                            printHeadersHelper("APEX", useSha256, pw);
                        }
                        String packageName = packageInfo.packageName;
                        pw.print(packageName + ","
                                + packageInfo.getLongVersionCode() + ",");
                        printPackageMeasurements(packageInfo, useSha256, pw);

                        if (verbose) {
                            ModuleInfo moduleInfo;
                            try {
                                moduleInfo = pm.getModuleInfo(packageInfo.packageName, 0);
                                pw.println("Is a module: true");
                                printModuleDetails(moduleInfo, pw);
                            } catch (PackageManager.NameNotFoundException e) {
                                pw.println("Is a module: false");
                            }

                            printPackageInstallationInfo(packageInfo, useSha256, pw);
                            printPackageSignerDetails(packageInfo.signingInfo, pw);
                            pw.println("");
                        }
                    }
                    return 0;
                }

                private int printAllModules() {
                    final PrintWriter pw = getOutPrintWriter();
                    boolean verbose = false;
                    boolean useSha256 = false;
                    boolean printHeaders = true;
                    String opt;
                    while ((opt = getNextOption()) != null) {
                        switch (opt) {
                            case "-v":
                            case "--verbose":
                                verbose = true;
                                break;
                            case "-o":
                            case "--old":
                                useSha256 = true;
                                break;
                            case "--no-headers":
                                printHeaders = false;
                                break;
                            default:
                                pw.println("ERROR: Unknown option: " + opt);
                                return 1;
                        }
                    }

                    PackageManager pm = mContext.getPackageManager();
                    if (pm == null) {
                        pw.println("ERROR: Failed to obtain an instance of package manager.");
                        return -1;
                    }

                    if (!verbose && printHeaders) {
                        printHeadersHelper("Module", useSha256, pw);
                    }
                    for (ModuleInfo module : pm.getInstalledModules(PackageManager.MATCH_ALL)) {
                        String packageName = module.getPackageName();
                        if (verbose && printHeaders) {
                            printHeadersHelper("Module", useSha256, pw);
                        }
                        try {
                            PackageInfo packageInfo = pm.getPackageInfo(packageName,
                                    PackageManager.MATCH_APEX
                                            | PackageManager.GET_SIGNING_CERTIFICATES);
                            pw.print(packageInfo.packageName + ",");
                            pw.print(packageInfo.getLongVersionCode() + ",");
                            printPackageMeasurements(packageInfo, useSha256, pw);

                            if (verbose) {
                                printModuleDetails(module, pw);
                                printPackageInstallationInfo(packageInfo, useSha256, pw);
                                printPackageSignerDetails(packageInfo.signingInfo, pw);
                                pw.println("");
                            }
                        } catch (PackageManager.NameNotFoundException e) {
                            pw.println(packageName
                                    + ",ERROR:Unable to find PackageInfo for this module.");
                            if (verbose) {
                                printModuleDetails(module, pw);
                                pw.println("");
                            }
                            continue;
                        }
                    }
                    return 0;
                }

                private int printAllMbas() {
                    final PrintWriter pw = getOutPrintWriter();
                    boolean verbose = false;
                    boolean printLibraries = false;
                    boolean useSha256 = false;
                    boolean printHeaders = true;
                    boolean preloadsOnly = false;
                    String opt;
                    while ((opt = getNextOption()) != null) {
                        switch (opt) {
                            case "-v":
                            case "--verbose":
                                verbose = true;
                                break;
                            case "-l":
                                printLibraries = true;
                                break;
                            case "-o":
                            case "--old":
                                useSha256 = true;
                                break;
                            case "--no-headers":
                                printHeaders = false;
                                break;
                            case "--preloads-only":
                                preloadsOnly = true;
                                break;
                            default:
                                pw.println("ERROR: Unknown option: " + opt);
                                return 1;
                        }
                    }

                    if (!verbose && printHeaders) {
                        if (preloadsOnly) {
                            printHeadersHelper("Preload", useSha256, pw);
                        } else {
                            printHeadersHelper("MBA", useSha256, pw);
                        }
                    }

                    PackageManager pm = mContext.getPackageManager();
                    for (PackageInfo packageInfo : pm.getInstalledPackages(
                            PackageManager.PackageInfoFlags.of(PackageManager.MATCH_FACTORY_ONLY
                            | PackageManager.GET_SIGNING_CERTIFICATES))) {
                        if (packageInfo.signingInfo == null) {
                            PackageInfo origPackageInfo = packageInfo;
                            try {
                                pm.getPackageInfo(packageInfo.packageName,
                                        PackageManager.PackageInfoFlags.of(PackageManager.MATCH_ALL
                                                | PackageManager.GET_SIGNING_CERTIFICATES));
                            } catch (PackageManager.NameNotFoundException e) {
                                Slog.e(TAG, "Failed to obtain an updated PackageInfo of "
                                        + origPackageInfo.packageName);
                                packageInfo = origPackageInfo;
                            }
                        }

                        if (verbose && printHeaders) {
                            printHeadersHelper("Preload", useSha256, pw);
                        }
                        pw.print(packageInfo.packageName + ",");
                        pw.print(packageInfo.getLongVersionCode() + ",");
                        printPackageMeasurements(packageInfo, useSha256, pw);

                        if (verbose) {
                            printAppDetails(packageInfo, printLibraries, pw);
                            printPackageInstallationInfo(packageInfo, useSha256, pw);
                            printPackageSignerDetails(packageInfo.signingInfo, pw);
                            pw.println("");
                        }
                    }

                    if (preloadsOnly) {
                        return 0;
                    }
                    for (PackageInfo packageInfo : getNewlyInstalledMbas()) {
                        if (verbose && printHeaders) {
                            printHeadersHelper("MBA", useSha256, pw);
                        }
                        pw.print(packageInfo.packageName + ",");
                        pw.print(packageInfo.getLongVersionCode() + ",");
                        printPackageMeasurements(packageInfo, useSha256, pw);

                        if (verbose) {
                            printAppDetails(packageInfo, printLibraries, pw);
                            printPackageInstallationInfo(packageInfo, useSha256, pw);
                            printPackageSignerDetails(packageInfo.signingInfo, pw);
                            pw.println("");
                        }
                    }
                    return 0;
                }

                @Override
                public int onCommand(String cmd) {
                    if (cmd == null) {
                        return handleDefaultCommands(cmd);
                    }

                    final PrintWriter pw = getOutPrintWriter();
                    switch (cmd) {
                        case "get": {
                            final String infoType = getNextArg();
                            if (infoType == null) {
                                printHelpMenu();
                                return -1;
                            }

                            switch (infoType) {
                                case "image_info":
                                    return printSignedImageInfo();
                                case "apex_info":
                                    return printAllApexs();
                                case "module_info":
                                    return printAllModules();
                                case "mba_info":
                                    return printAllMbas();
                                default:
                                    pw.println(String.format("ERROR: Unknown info type '%s'",
                                            infoType));
                                    return 1;
                            }
                        }
                        default:
                            return handleDefaultCommands(cmd);
                    }
                }

                private void printHelpMenu() {
                    final PrintWriter pw = getOutPrintWriter();
                    pw.println("Transparency manager (transparency) commands:");
                    pw.println("  help");
                    pw.println("    Print this help text.");
                    pw.println("");
                    pw.println("  get image_info [-a]");
                    pw.println("    Print information about loaded image (firmware). Options:");
                    pw.println("        -a: lists all other identifiable partitions.");
                    pw.println("");
                    pw.println("  get apex_info [-o] [-v] [--no-headers]");
                    pw.println("    Print information about installed APEXs on device.");
                    pw.println("      -o: also uses the old digest scheme (SHA256) to compute "
                               + "APEX hashes. WARNING: This can be a very slow and CPU-intensive "
                               + "computation.");
                    pw.println("      -v: lists more verbose information about each APEX.");
                    pw.println("      --no-headers: does not print the header if specified.");
                    pw.println("");
                    pw.println("  get module_info [-o] [-v] [--no-headers]");
                    pw.println("    Print information about installed modules on device.");
                    pw.println("      -o: also uses the old digest scheme (SHA256) to compute "
                               + "module hashes. WARNING: This can be a very slow and "
                               + "CPU-intensive computation.");
                    pw.println("      -v: lists more verbose information about each module.");
                    pw.println("      --no-headers: does not print the header if specified.");
                    pw.println("");
                    pw.println("  get mba_info [-o] [-v] [-l] [--no-headers] [--preloads-only]");
                    pw.println("    Print information about installed mobile bundle apps "
                               + "(MBAs on device).");
                    pw.println("      -o: also uses the old digest scheme (SHA256) to compute "
                               + "MBA hashes. WARNING: This can be a very slow and CPU-intensive "
                               + "computation.");
                    pw.println("      -v: lists more verbose information about each app.");
                    pw.println("      -l: lists shared library info. (This option only works "
                               + "when -v option is also specified)");
                    pw.println("      --no-headers: does not print the header if specified.");
                    pw.println("      --preloads-only: lists only preloaded apps. This options can "
                               + "also be combined with others.");
                    pw.println("");
                }

                @Override
                public void onHelp() {
                    printHelpMenu();
                }
            }).exec(this, in, out, err, args, callback, resultReceiver);
        }
    }
    private final BinaryTransparencyServiceImpl mServiceImpl;

    /**
     * A wrapper of {@link FrameworkStatsLog} for easier testing
     */
    @VisibleForTesting
    public static class BiometricLogger {
        private static final String TAG = "BiometricLogger";

        private static final BiometricLogger sInstance = new BiometricLogger();

        private BiometricLogger() {}

        public static BiometricLogger getInstance() {
            return sInstance;
        }

        /**
         * A wrapper of {@link FrameworkStatsLog}
         *
         * @param sensorId The sensorId of the biometric to be logged
         * @param modality The modality of the biometric
         * @param sensorType The sensor type of the biometric
         * @param sensorStrength The sensor strength of the biometric
         * @param componentId The component Id of a component of the biometric
         * @param hardwareVersion The hardware version of a component of the biometric
         * @param firmwareVersion The firmware version of a component of the biometric
         * @param serialNumber The serial number of a component of the biometric
         * @param softwareVersion The software version of a component of the biometric
         */
        public void logStats(int sensorId, int modality, int sensorType, int sensorStrength,
                String componentId, String hardwareVersion, String firmwareVersion,
                String serialNumber, String softwareVersion) {
            FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_PROPERTIES_COLLECTED,
                    sensorId, modality, sensorType, sensorStrength, componentId, hardwareVersion,
                    firmwareVersion, serialNumber, softwareVersion);
        }
    }

    public BinaryTransparencyService(Context context) {
        this(context, BiometricLogger.getInstance());
    }

    @VisibleForTesting
    BinaryTransparencyService(Context context, BiometricLogger biometricLogger) {
        super(context);
        mContext = context;
        mServiceImpl = new BinaryTransparencyServiceImpl();
        mVbmetaDigest = VBMETA_DIGEST_UNINITIALIZED;
        mMeasurementsLastRecordedMs = 0;
        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        mBiometricLogger = biometricLogger;
    }

    /**
     * Called when the system service should publish a binder service using
     * {@link #publishBinderService(String, IBinder).}
     */
    @Override
    public void onStart() {
        try {
            publishBinderService(Context.BINARY_TRANSPARENCY_SERVICE, mServiceImpl);
            Slog.i(TAG, "Started BinaryTransparencyService");
        } catch (Throwable t) {
            Slog.e(TAG, "Failed to start BinaryTransparencyService.", t);
        }
    }

    /**
     * Called on each phase of the boot process. Phases before the service's start phase
     * (as defined in the @Service annotation) are never received.
     *
     * @param phase The current boot phase.
     */
    @Override
    public void onBootPhase(int phase) {

        // we are only interested in doing things at PHASE_BOOT_COMPLETED
        if (phase == PHASE_BOOT_COMPLETED) {
            Slog.i(TAG, "Boot completed. Getting boot integrity data.");
            collectBootIntegrityInfo();

            // Log to statsd
            // TODO(b/264061957): For now, biometric system properties are always collected if users
            //  share usage & diagnostics information. In the future, collect biometric system
            //  properties only when transparency log verification of the target partitions fails
            //  (e.g. when the system/vendor partitions have been changed) once the binary
            //  transparency infrastructure is ready.
            Slog.i(TAG, "Boot completed. Collecting biometric system properties.");
            collectBiometricProperties();

            // to avoid the risk of holding up boot time, computations to measure APEX, Module, and
            // MBA digests are scheduled here, but only executed when the device is idle and plugged
            // in.
            Slog.i(TAG, "Scheduling measurements to be taken.");
            UpdateMeasurementsJobService.scheduleBinaryMeasurements(mContext,
                    BinaryTransparencyService.this);

            registerAllPackageUpdateObservers();
        }
    }

    /**
     * JobService to measure all covered binaries and record results to statsd.
     */
    public static class UpdateMeasurementsJobService extends JobService {
        private static long sTimeLastRanMs = 0;
        private static final int DO_BINARY_MEASUREMENTS_JOB_ID = 1740526926;

        @Override
        public boolean onStartJob(JobParameters params) {
            Slog.d(TAG, "Job to update binary measurements started.");
            if (params.getJobId() != DO_BINARY_MEASUREMENTS_JOB_ID) {
                return false;
            }

            // we'll perform binary measurements via threads to be mindful of low-end devices
            // where this operation might take longer than expected, and so that we don't block
            // system_server's main thread.
            Executors.defaultThreadFactory().newThread(() -> {
                IBinder b = ServiceManager.getService(Context.BINARY_TRANSPARENCY_SERVICE);
                IBinaryTransparencyService iBtsService =
                        IBinaryTransparencyService.Stub.asInterface(b);
                try {
                    iBtsService.recordMeasurementsForAllPackages();
                } catch (RemoteException e) {
                    Slog.e(TAG, "Taking binary measurements was interrupted.", e);
                    return;
                }
                sTimeLastRanMs = System.currentTimeMillis();
                jobFinished(params, false);
            }).start();

            return true;
        }

        @Override
        public boolean onStopJob(JobParameters params) {
            return false;
        }

        @SuppressLint("DefaultLocale")
        static void scheduleBinaryMeasurements(Context context, BinaryTransparencyService service) {
            Slog.i(TAG, "Scheduling binary content-digest computation job");
            final JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
            if (jobScheduler == null) {
                Slog.e(TAG, "Failed to obtain an instance of JobScheduler.");
                return;
            }

            if (jobScheduler.getPendingJob(DO_BINARY_MEASUREMENTS_JOB_ID) != null) {
                Slog.d(TAG, "A measurement job has already been scheduled.");
                return;
            }

            long minWaitingPeriodMs = 0;
            if (sTimeLastRanMs != 0) {
                minWaitingPeriodMs = RECORD_MEASUREMENTS_COOLDOWN_MS
                        - (System.currentTimeMillis() - sTimeLastRanMs);
                // bound the range of minWaitingPeriodMs in the case where > 24h has elapsed
                minWaitingPeriodMs = Math.max(0,
                        Math.min(minWaitingPeriodMs, RECORD_MEASUREMENTS_COOLDOWN_MS));
                Slog.d(TAG, "Scheduling the next measurement to be done at least "
                        + minWaitingPeriodMs + "ms from now.");
            }

            final JobInfo jobInfo = new JobInfo.Builder(DO_BINARY_MEASUREMENTS_JOB_ID,
                    new ComponentName(context, UpdateMeasurementsJobService.class))
                    .setRequiresDeviceIdle(true)
                    .setRequiresCharging(true)
                    .setMinimumLatency(minWaitingPeriodMs)
                    .build();
            if (jobScheduler.schedule(jobInfo) != JobScheduler.RESULT_SUCCESS) {
                Slog.e(TAG, "Failed to schedule job to measure binaries.");
                return;
            }
            Slog.d(TAG, TextUtils.formatSimple(
                    "Job %d to measure binaries was scheduled successfully.",
                    DO_BINARY_MEASUREMENTS_JOB_ID));
        }
    }

    /**
     * Convert a {@link FingerprintSensorProperties} sensor type to the corresponding enum to be
     * logged.
     *
     * @param sensorType See {@link FingerprintSensorProperties}
     * @return The enum to be logged
     */
    private int toFingerprintSensorType(@FingerprintSensorProperties.SensorType int sensorType) {
        switch (sensorType) {
            case FingerprintSensorProperties.TYPE_REAR:
                return FrameworkStatsLog
                        .BIOMETRIC_PROPERTIES_COLLECTED__SENSOR_TYPE__SENSOR_FP_REAR;
            case FingerprintSensorProperties.TYPE_UDFPS_ULTRASONIC:
                return FrameworkStatsLog
                        .BIOMETRIC_PROPERTIES_COLLECTED__SENSOR_TYPE__SENSOR_FP_UDFPS_ULTRASONIC;
            case FingerprintSensorProperties.TYPE_UDFPS_OPTICAL:
                return FrameworkStatsLog
                        .BIOMETRIC_PROPERTIES_COLLECTED__SENSOR_TYPE__SENSOR_FP_UDFPS_OPTICAL;
            case FingerprintSensorProperties.TYPE_POWER_BUTTON:
                return FrameworkStatsLog
                        .BIOMETRIC_PROPERTIES_COLLECTED__SENSOR_TYPE__SENSOR_FP_POWER_BUTTON;
            case FingerprintSensorProperties.TYPE_HOME_BUTTON:
                return FrameworkStatsLog
                        .BIOMETRIC_PROPERTIES_COLLECTED__SENSOR_TYPE__SENSOR_FP_HOME_BUTTON;
            default:
                return FrameworkStatsLog
                        .BIOMETRIC_PROPERTIES_COLLECTED__SENSOR_TYPE__SENSOR_UNKNOWN;
        }
    }

    /**
     * Convert a {@link FaceSensorProperties} sensor type to the corresponding enum to be logged.
     *
     * @param sensorType See {@link FaceSensorProperties}
     * @return The enum to be logged
     */
    private int toFaceSensorType(@FaceSensorProperties.SensorType int sensorType) {
        switch (sensorType) {
            case FaceSensorProperties.TYPE_RGB:
                return FrameworkStatsLog
                        .BIOMETRIC_PROPERTIES_COLLECTED__SENSOR_TYPE__SENSOR_FACE_RGB;
            case FaceSensorProperties.TYPE_IR:
                return FrameworkStatsLog
                        .BIOMETRIC_PROPERTIES_COLLECTED__SENSOR_TYPE__SENSOR_FACE_IR;
            default:
                return FrameworkStatsLog
                        .BIOMETRIC_PROPERTIES_COLLECTED__SENSOR_TYPE__SENSOR_UNKNOWN;
        }
    }

    /**
     * Convert a {@link SensorProperties} sensor strength to the corresponding enum to be logged.
     *
     * @param sensorStrength See {@link SensorProperties}
     * @return The enum to be logged
     */
    private int toSensorStrength(@SensorProperties.Strength int sensorStrength) {
        switch (sensorStrength) {
            case SensorProperties.STRENGTH_CONVENIENCE:
                return FrameworkStatsLog
                        .BIOMETRIC_PROPERTIES_COLLECTED__SENSOR_STRENGTH__STRENGTH_CONVENIENCE;
            case SensorProperties.STRENGTH_WEAK:
                return FrameworkStatsLog
                        .BIOMETRIC_PROPERTIES_COLLECTED__SENSOR_STRENGTH__STRENGTH_WEAK;
            case SensorProperties.STRENGTH_STRONG:
                return FrameworkStatsLog
                        .BIOMETRIC_PROPERTIES_COLLECTED__SENSOR_STRENGTH__STRENGTH_STRONG;
            default:
                return FrameworkStatsLog
                        .BIOMETRIC_PROPERTIES_COLLECTED__SENSOR_STRENGTH__STRENGTH_UNKNOWN;
        }
    }

    /**
     * A helper function to log detailed biometric sensor properties to statsd.
     *
     * @param prop The biometric sensor properties to be logged
     * @param modality The modality of the biometric (e.g. fingerprint, face) to be logged
     * @param sensorType The specific type of the biometric to be logged
     */
    private void logBiometricProperties(SensorProperties prop, int modality, int sensorType) {
        final int sensorId = prop.getSensorId();
        final int sensorStrength = toSensorStrength(prop.getSensorStrength());

        // Log data for each component
        // Note: none of the component info is a device identifier since every device of a given
        // model and build share the same biometric system info (see b/216195167)
        for (ComponentInfo componentInfo : prop.getComponentInfo()) {
            mBiometricLogger.logStats(
                    sensorId,
                    modality,
                    sensorType,
                    sensorStrength,
                    componentInfo.getComponentId().trim(),
                    componentInfo.getHardwareVersion().trim(),
                    componentInfo.getFirmwareVersion().trim(),
                    componentInfo.getSerialNumber().trim(),
                    componentInfo.getSoftwareVersion().trim());
        }
    }

    @VisibleForTesting
    void collectBiometricProperties() {
        // Check the flag to determine whether biometric property verification is enabled. It's
        // disabled by default.
        if (!DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_BIOMETRICS,
                KEY_ENABLE_BIOMETRIC_PROPERTY_VERIFICATION, true)) {
            if (DEBUG) {
                Slog.d(TAG, "Do not collect/verify biometric properties. Feature disabled by "
                        + "DeviceConfig");
            }
            return;
        }

        PackageManager pm = mContext.getPackageManager();
        FingerprintManager fpManager = null;
        FaceManager faceManager = null;
        if (pm != null && pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
            fpManager = mContext.getSystemService(FingerprintManager.class);
        }
        if (pm != null && pm.hasSystemFeature(PackageManager.FEATURE_FACE)) {
            faceManager = mContext.getSystemService(FaceManager.class);
        }

        if (fpManager != null) {
            final int fpModality = FrameworkStatsLog
                    .BIOMETRIC_PROPERTIES_COLLECTED__MODALITY__MODALITY_FINGERPRINT;
            fpManager.addAuthenticatorsRegisteredCallback(
                    new IFingerprintAuthenticatorsRegisteredCallback.Stub() {
                        @Override
                        public void onAllAuthenticatorsRegistered(
                                List<FingerprintSensorPropertiesInternal> sensors) {
                            if (DEBUG) {
                                Slog.d(TAG, "Retrieve fingerprint sensor properties. "
                                        + "sensors.size()=" + sensors.size());
                            }
                            // Log data for each fingerprint sensor
                            for (FingerprintSensorPropertiesInternal propInternal : sensors) {
                                final FingerprintSensorProperties prop =
                                        FingerprintSensorProperties.from(propInternal);
                                logBiometricProperties(prop,
                                        fpModality,
                                        toFingerprintSensorType(prop.getSensorType()));
                            }
                        }
                    });
        }

        if (faceManager != null) {
            final int faceModality = FrameworkStatsLog
                    .BIOMETRIC_PROPERTIES_COLLECTED__MODALITY__MODALITY_FACE;
            faceManager.addAuthenticatorsRegisteredCallback(
                    new IFaceAuthenticatorsRegisteredCallback.Stub() {
                        @Override
                        public void onAllAuthenticatorsRegistered(
                                List<FaceSensorPropertiesInternal> sensors) {
                            if (DEBUG) {
                                Slog.d(TAG, "Retrieve face sensor properties. sensors.size()="
                                        + sensors.size());
                            }
                            // Log data for each face sensor
                            for (FaceSensorPropertiesInternal propInternal : sensors) {
                                final FaceSensorProperties prop =
                                        FaceSensorProperties.from(propInternal);
                                logBiometricProperties(prop,
                                        faceModality,
                                        toFaceSensorType(prop.getSensorType()));
                            }
                        }
                    });
        }
    }

    private void collectBootIntegrityInfo() {
        mVbmetaDigest = SystemProperties.get(SYSPROP_NAME_VBETA_DIGEST, VBMETA_DIGEST_UNAVAILABLE);
        Slog.d(TAG, String.format("VBMeta Digest: %s", mVbmetaDigest));
        FrameworkStatsLog.write(FrameworkStatsLog.VBMETA_DIGEST_REPORTED, mVbmetaDigest);

        if (android.security.Flags.binaryTransparencySepolicyHash()) {
            IoThread.getExecutor().execute(() -> {
                byte[] sepolicyHash = PackageUtils.computeSha256DigestForLargeFileAsBytes(
                        "/sys/fs/selinux/policy", PackageUtils.createLargeFileBuffer());
                String sepolicyHashEncoded = null;
                if (sepolicyHash != null) {
                    sepolicyHashEncoded = HexEncoding.encodeToString(sepolicyHash, false);
                    Slog.d(TAG, "sepolicy hash: " + sepolicyHashEncoded);
                }
                FrameworkStatsLog.write(FrameworkStatsLog.BOOT_INTEGRITY_INFO_REPORTED,
                        sepolicyHashEncoded, mVbmetaDigest);
            });
        }
    }

    /**
     * Listen for APK updates.
     *
     * There are two ways available to us to do this:
     * 1. Register an observer using
     * {@link PackageManagerInternal#getPackageList(PackageManagerInternal.PackageListObserver)}.
     * 2. Register broadcast receivers, listening to either {@code ACTION_PACKAGE_ADDED} or
     * {@code ACTION_PACKAGE_REPLACED}.
     *
     * After experimentation, we found that Option #1 does not catch updates to non-staged APEXs.
     * Thus, we are implementing Option #2 here. More specifically, listening to
     * {@link Intent#ACTION_PACKAGE_ADDED} allows us to capture all events we care about.
     *
     * We did not use {@link Intent#ACTION_PACKAGE_REPLACED} because it unfortunately does not
     * detect updates to non-staged APEXs. Thus, we rely on {@link Intent#EXTRA_REPLACING} to
     * filter out new installation from updates instead.
     */
    private void registerApkAndNonStagedApexUpdateListener() {
        Slog.d(TAG, "Registering APK & Non-Staged APEX updates...");
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addDataScheme("package");    // this is somehow necessary
        mContext.registerReceiver(new PackageUpdatedReceiver(), filter);
    }

    /**
     * Listen for staged-APEX updates.
     *
     * This method basically covers cases that are not caught by
     * {@link #registerApkAndNonStagedApexUpdateListener()}, namely updates to APEXs that are staged
     * for the subsequent reboot.
     */
    private void registerStagedApexUpdateObserver() {
        Slog.d(TAG, "Registering APEX updates...");
        IPackageManagerNative iPackageManagerNative = IPackageManagerNative.Stub.asInterface(
                ServiceManager.getService("package_native"));
        if (iPackageManagerNative == null) {
            Slog.e(TAG, "IPackageManagerNative is null");
            return;
        }

        try {
            iPackageManagerNative.registerStagedApexObserver(new IStagedApexObserver.Stub() {
                @Override
                public void onApexStaged(ApexStagedEvent event) throws RemoteException {
                    Slog.d(TAG, "A new APEX has been staged for update. There are currently "
                            + event.stagedApexModuleNames.length + " APEX(s) staged for update. "
                            + "Scheduling measurement...");
                    UpdateMeasurementsJobService.scheduleBinaryMeasurements(mContext,
                            BinaryTransparencyService.this);
                }
            });
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to register a StagedApexObserver.");
        }
    }

    private boolean isPackagePreloaded(String packageName) {
        PackageManager pm = mContext.getPackageManager();
        try {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(
                    PackageManager.MATCH_FACTORY_ONLY));
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        return true;
    }

    private boolean isPackageAnApex(String packageName) {
        PackageManager pm = mContext.getPackageManager();
        try {
            PackageInfo packageInfo = pm.getPackageInfo(packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.MATCH_APEX));
            return packageInfo.isApex;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private class PackageUpdatedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!intent.getAction().equals(Intent.ACTION_PACKAGE_ADDED)) {
                return;
            }

            Uri data = intent.getData();
            if (data == null) {
                Slog.e(TAG, "Shouldn't happen: intent data is null!");
                return;
            }

            if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                Slog.d(TAG, "Not an update. Skipping...");
                return;
            }

            String packageName = data.getSchemeSpecificPart();
            // now we've got to check what package is this
            if (isPackagePreloaded(packageName) || isPackageAnApex(packageName)) {
                Slog.d(TAG, packageName + " was updated. Scheduling measurement...");
                UpdateMeasurementsJobService.scheduleBinaryMeasurements(mContext,
                        BinaryTransparencyService.this);
            }
        }
    }

    /**
     * Register observers for APK and APEX updates. The current implementation breaks this process
     * into 2 cases/methods because PackageManager does not offer a unified interface to register
     * for all package updates in a universal and comprehensive manner.
     * Thus, the observers will be invoked when either
     * i) APK or non-staged APEX update; or
     * ii) APEX staging happens.
     * This will then be used as signals to schedule measurement for the relevant binaries.
     */
    private void registerAllPackageUpdateObservers() {
        registerApkAndNonStagedApexUpdateListener();
        registerStagedApexUpdateObserver();
    }

    private String translateContentDigestAlgorithmIdToString(int algorithmId) {
        switch (algorithmId) {
            case ApkSigningBlockUtils.CONTENT_DIGEST_CHUNKED_SHA256:
                return "CHUNKED_SHA256";
            case ApkSigningBlockUtils.CONTENT_DIGEST_CHUNKED_SHA512:
                return "CHUNKED_SHA512";
            case ApkSigningBlockUtils.CONTENT_DIGEST_VERITY_CHUNKED_SHA256:
                return "VERITY_CHUNKED_SHA256";
            case ApkSigningBlockUtils.CONTENT_DIGEST_SHA256:
                return "SHA256";
            default:
                return "UNKNOWN_ALGO_ID(" + algorithmId + ")";
        }
    }

    @NonNull
    private List<PackageInfo> getCurrentInstalledApexs() {
        List<PackageInfo> results = new ArrayList<>();
        PackageManager pm = mContext.getPackageManager();
        if (pm == null) {
            Slog.e(TAG, "Error obtaining an instance of PackageManager.");
            return results;
        }
        List<PackageInfo> allPackages = pm.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(PackageManager.MATCH_APEX
                        | PackageManager.GET_SIGNING_CERTIFICATES));
        if (allPackages == null) {
            Slog.e(TAG, "Error obtaining installed packages (including APEX)");
            return results;
        }

        results = allPackages.stream().filter(p -> p.isApex).collect(Collectors.toList());
        return results;
    }

    @Nullable
    private InstallSourceInfo getInstallSourceInfo(String packageName) {
        PackageManager pm = mContext.getPackageManager();
        if (pm == null) {
            Slog.e(TAG, "Error obtaining an instance of PackageManager.");
            return null;
        }
        try {
            return pm.getInstallSourceInfo(packageName);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }

    @NonNull
    private String getOriginalApexPreinstalledLocation(String packageName) {
        try {
            final String moduleName = apexPackageNameToModuleName(packageName);
            IApexService apexService = IApexService.Stub.asInterface(
                    Binder.allowBlocking(ServiceManager.waitForService("apexservice")));
            for (ApexInfo info : apexService.getAllPackages()) {
                if (moduleName.equals(info.moduleName)) {
                    return info.preinstalledModulePath;
                }
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to get package list from apexservice", e);
        }
        return APEX_PRELOAD_LOCATION_ERROR;
    }

    private String apexPackageNameToModuleName(String packageName) {
        // It appears that only apexd knows the preinstalled location, and it uses module name as
        // the identifier instead of package name. Given the input is a package name, we need to
        // covert to module name.
        return ApexManager.getInstance().getApexModuleNameForPackageName(packageName);
    }

    /**
     * Wrapper method to call into IBICS to get a list of all newly installed MBAs.
     *
     * We expect IBICS to maintain an accurate list of installed MBAs, and we merely make use of
     * the results within this service. This means we do not further check whether the
     * apps in the returned slice is still installed or not, esp. considering that preloaded apps
     * could be updated, or post-setup installed apps *might* be deleted in real time.
     *
     * Note that we do *not* cache the results from IBICS because of the more dynamic nature of
     * MBAs v.s. other binaries that we measure.
     *
     * @return a list of preloaded apps + dynamically installed apps that fit the definition of MBA.
     */
    @NonNull
    private List<PackageInfo> getNewlyInstalledMbas() {
        List<PackageInfo> result = new ArrayList<>();
        IBackgroundInstallControlService iBics = IBackgroundInstallControlService.Stub.asInterface(
                ServiceManager.getService(Context.BACKGROUND_INSTALL_CONTROL_SERVICE));
        if (iBics == null) {
            Slog.e(TAG,
                    "Failed to obtain an IBinder instance of IBackgroundInstallControlService");
            return result;
        }
        ParceledListSlice<PackageInfo> slice;
        try {
            slice = iBics.getBackgroundInstalledPackages(
                    PackageManager.MATCH_ALL | PackageManager.GET_SIGNING_CERTIFICATES,
                    UserHandle.USER_SYSTEM);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to get a list of MBAs.", e);
            return result;
        }
        return slice.getList();
    }

    private record Digest(int algorithm, byte[] value) {}
}
