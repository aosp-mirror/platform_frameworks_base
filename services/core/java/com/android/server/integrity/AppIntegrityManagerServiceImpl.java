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

package com.android.server.integrity;

import static android.content.Intent.ACTION_PACKAGE_NEEDS_INTEGRITY_VERIFICATION;
import static android.content.Intent.EXTRA_LONG_VERSION_CODE;
import static android.content.Intent.EXTRA_ORIGINATING_UID;
import static android.content.Intent.EXTRA_PACKAGE_NAME;
import static android.content.integrity.AppIntegrityManager.EXTRA_STATUS;
import static android.content.integrity.AppIntegrityManager.STATUS_FAILURE;
import static android.content.integrity.AppIntegrityManager.STATUS_SUCCESS;
import static android.content.integrity.InstallerAllowedByManifestFormula.INSTALLER_CERTIFICATE_NOT_EVALUATED;
import static android.content.integrity.IntegrityUtils.getHexDigest;
import static android.content.pm.PackageManager.EXTRA_VERIFICATION_ID;

import android.annotation.BinderThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.integrity.AppInstallMetadata;
import android.content.integrity.IAppIntegrityManager;
import android.content.integrity.Rule;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageUserState;
import android.content.pm.ParceledListSlice;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.content.pm.parsing.ParsingPackageUtils;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.util.apk.SourceStampVerificationResult;
import android.util.apk.SourceStampVerifier;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.LocalServices;
import com.android.server.integrity.engine.RuleEvaluationEngine;
import com.android.server.integrity.model.IntegrityCheckResult;
import com.android.server.integrity.model.RuleMetadata;
import com.android.server.pm.parsing.PackageInfoUtils;
import com.android.server.pm.parsing.PackageParser2;
import com.android.server.pm.parsing.pkg.ParsedPackage;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Implementation of {@link AppIntegrityManagerService}. */
public class AppIntegrityManagerServiceImpl extends IAppIntegrityManager.Stub {
    /**
     * This string will be used as the "installer" for formula evaluation when the app's installer
     * cannot be determined.
     *
     * <p>This may happen for various reasons. e.g., the installing app's package name may not match
     * its UID.
     */
    private static final String UNKNOWN_INSTALLER = "";
    /**
     * This string will be used as the "installer" for formula evaluation when the app is being
     * installed via ADB.
     */
    public static final String ADB_INSTALLER = "adb";

    private static final String TAG = "AppIntegrityManagerServiceImpl";

    private static final String PACKAGE_MIME_TYPE = "application/vnd.android.package-archive";
    private static final String BASE_APK_FILE = "base.apk";
    private static final String ALLOWED_INSTALLERS_METADATA_NAME = "allowed-installers";
    private static final String ALLOWED_INSTALLER_DELIMITER = ",";
    private static final String INSTALLER_PACKAGE_CERT_DELIMITER = "\\|";

    public static final boolean DEBUG_INTEGRITY_COMPONENT = false;

    private static final Set<String> PACKAGE_INSTALLER =
            new HashSet<>(
                    Arrays.asList(
                            "com.google.android.packageinstaller", "com.android.packageinstaller"));

    // Access to files inside mRulesDir is protected by mRulesLock;
    private final Context mContext;
    private final Handler mHandler;
    private final PackageManagerInternal mPackageManagerInternal;
    private final Supplier<PackageParser2> mParserSupplier;
    private final RuleEvaluationEngine mEvaluationEngine;
    private final IntegrityFileManager mIntegrityFileManager;

    /** Create an instance of {@link AppIntegrityManagerServiceImpl}. */
    public static AppIntegrityManagerServiceImpl create(Context context) {
        HandlerThread handlerThread = new HandlerThread("AppIntegrityManagerServiceHandler");
        handlerThread.start();

        return new AppIntegrityManagerServiceImpl(
                context,
                LocalServices.getService(PackageManagerInternal.class),
                PackageParser2::forParsingFileWithDefaults,
                RuleEvaluationEngine.getRuleEvaluationEngine(),
                IntegrityFileManager.getInstance(),
                handlerThread.getThreadHandler());
    }

    @VisibleForTesting
    AppIntegrityManagerServiceImpl(
            Context context,
            PackageManagerInternal packageManagerInternal,
            Supplier<PackageParser2> parserSupplier,
            RuleEvaluationEngine evaluationEngine,
            IntegrityFileManager integrityFileManager,
            Handler handler) {
        mContext = context;
        mPackageManagerInternal = packageManagerInternal;
        mParserSupplier = parserSupplier;
        mEvaluationEngine = evaluationEngine;
        mIntegrityFileManager = integrityFileManager;
        mHandler = handler;

        IntentFilter integrityVerificationFilter = new IntentFilter();
        integrityVerificationFilter.addAction(ACTION_PACKAGE_NEEDS_INTEGRITY_VERIFICATION);
        try {
            integrityVerificationFilter.addDataType(PACKAGE_MIME_TYPE);
        } catch (IntentFilter.MalformedMimeTypeException e) {
            throw new RuntimeException("Mime type malformed: should never happen.", e);
        }

        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (!ACTION_PACKAGE_NEEDS_INTEGRITY_VERIFICATION.equals(
                                intent.getAction())) {
                            return;
                        }
                        mHandler.post(() -> handleIntegrityVerification(intent));
                    }
                },
                integrityVerificationFilter,
                /* broadcastPermission= */ null,
                mHandler);
    }

    @Override
    @BinderThread
    public void updateRuleSet(
            String version, ParceledListSlice<Rule> rules, IntentSender statusReceiver) {
        String ruleProvider = getCallerPackageNameOrThrow(Binder.getCallingUid());
        if (DEBUG_INTEGRITY_COMPONENT) {
            Slog.i(TAG, String.format("Calling rule provider name is: %s.", ruleProvider));
        }

        mHandler.post(
                () -> {
                    boolean success = true;
                    try {
                        mIntegrityFileManager.writeRules(version, ruleProvider, rules.getList());
                    } catch (Exception e) {
                        Slog.e(TAG, "Error writing rules.", e);
                        success = false;
                    }

                    if (DEBUG_INTEGRITY_COMPONENT) {
                        Slog.i(
                                TAG,
                                String.format(
                                        "Successfully pushed rule set to version '%s' from '%s'",
                                        version, ruleProvider));
                    }

                    FrameworkStatsLog.write(
                            FrameworkStatsLog.INTEGRITY_RULES_PUSHED,
                            success,
                            ruleProvider,
                            version);

                    Intent intent = new Intent();
                    intent.putExtra(EXTRA_STATUS, success ? STATUS_SUCCESS : STATUS_FAILURE);
                    try {
                        statusReceiver.sendIntent(
                                mContext,
                                /* code= */ 0,
                                intent,
                                /* onFinished= */ null,
                                /* handler= */ null);
                    } catch (Exception e) {
                        Slog.e(TAG, "Error sending status feedback.", e);
                    }
                });
    }

    @Override
    @BinderThread
    public String getCurrentRuleSetVersion() {
        getCallerPackageNameOrThrow(Binder.getCallingUid());

        RuleMetadata ruleMetadata = mIntegrityFileManager.readMetadata();
        return (ruleMetadata != null && ruleMetadata.getVersion() != null)
                ? ruleMetadata.getVersion()
                : "";
    }

    @Override
    @BinderThread
    public String getCurrentRuleSetProvider() {
        getCallerPackageNameOrThrow(Binder.getCallingUid());

        RuleMetadata ruleMetadata = mIntegrityFileManager.readMetadata();
        return (ruleMetadata != null && ruleMetadata.getRuleProvider() != null)
                ? ruleMetadata.getRuleProvider()
                : "";
    }

    @Override
    public ParceledListSlice<Rule> getCurrentRules() {
        List<Rule> rules = Collections.emptyList();
        try {
            rules = mIntegrityFileManager.readRules(/* appInstallMetadata= */ null);
        } catch (Exception e) {
            Slog.e(TAG, "Error getting current rules", e);
        }
        return new ParceledListSlice<>(rules);
    }

    @Override
    public List<String> getWhitelistedRuleProviders() {
        return getAllowedRuleProviderSystemApps();
    }

    private void handleIntegrityVerification(Intent intent) {
        int verificationId = intent.getIntExtra(EXTRA_VERIFICATION_ID, -1);

        try {
            if (DEBUG_INTEGRITY_COMPONENT) {
                Slog.d(TAG, "Received integrity verification intent " + intent.toString());
                Slog.d(TAG, "Extras " + intent.getExtras());
            }

            String installerPackageName = getInstallerPackageName(intent);

            // Skip integrity verification if the verifier is doing the install.
            if (!integrityCheckIncludesRuleProvider() && isRuleProvider(installerPackageName)) {
                if (DEBUG_INTEGRITY_COMPONENT) {
                    Slog.i(TAG, "Verifier doing the install. Skipping integrity check.");
                }
                mPackageManagerInternal.setIntegrityVerificationResult(
                        verificationId, PackageManagerInternal.INTEGRITY_VERIFICATION_ALLOW);
                return;
            }

            String packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME);

            PackageInfo packageInfo = getPackageArchiveInfo(intent.getData());
            if (packageInfo == null) {
                Slog.w(TAG, "Cannot parse package " + packageName);
                // We can't parse the package.
                mPackageManagerInternal.setIntegrityVerificationResult(
                        verificationId, PackageManagerInternal.INTEGRITY_VERIFICATION_ALLOW);
                return;
            }

            List<String> appCertificates = getCertificateFingerprint(packageInfo);
            List<String> installerCertificates =
                    getInstallerCertificateFingerprint(installerPackageName);

            AppInstallMetadata.Builder builder = new AppInstallMetadata.Builder();

            builder.setPackageName(getPackageNameNormalized(packageName));
            builder.setAppCertificates(appCertificates);
            builder.setVersionCode(intent.getLongExtra(EXTRA_LONG_VERSION_CODE, -1));
            builder.setInstallerName(getPackageNameNormalized(installerPackageName));
            builder.setInstallerCertificates(installerCertificates);
            builder.setIsPreInstalled(isSystemApp(packageName));
            builder.setAllowedInstallersAndCert(getAllowedInstallers(packageInfo));
            extractSourceStamp(intent.getData(), builder);

            AppInstallMetadata appInstallMetadata = builder.build();

            if (DEBUG_INTEGRITY_COMPONENT) {
                Slog.i(
                        TAG,
                        "To be verified: "
                                + appInstallMetadata
                                + " installers "
                                + getAllowedInstallers(packageInfo));
            }
            IntegrityCheckResult result = mEvaluationEngine.evaluate(appInstallMetadata);
            if (!result.getMatchedRules().isEmpty() || DEBUG_INTEGRITY_COMPONENT) {
                Slog.i(
                        TAG,
                        String.format(
                                "Integrity check of %s result: %s due to %s",
                                packageName, result.getEffect(), result.getMatchedRules()));
            }

            FrameworkStatsLog.write(
                    FrameworkStatsLog.INTEGRITY_CHECK_RESULT_REPORTED,
                    packageName,
                    appCertificates.toString(),
                    appInstallMetadata.getVersionCode(),
                    installerPackageName,
                    result.getLoggingResponse(),
                    result.isCausedByAppCertRule(),
                    result.isCausedByInstallerRule());
            mPackageManagerInternal.setIntegrityVerificationResult(
                    verificationId,
                    result.getEffect() == IntegrityCheckResult.Effect.ALLOW
                            ? PackageManagerInternal.INTEGRITY_VERIFICATION_ALLOW
                            : PackageManagerInternal.INTEGRITY_VERIFICATION_REJECT);
        } catch (IllegalArgumentException e) {
            // This exception indicates something is wrong with the input passed by package manager.
            // e.g., someone trying to trick the system. We block installs in this case.
            Slog.e(TAG, "Invalid input to integrity verification", e);
            mPackageManagerInternal.setIntegrityVerificationResult(
                    verificationId, PackageManagerInternal.INTEGRITY_VERIFICATION_REJECT);
        } catch (Exception e) {
            // Other exceptions indicate an error within the integrity component implementation and
            // we allow them.
            Slog.e(TAG, "Error handling integrity verification", e);
            mPackageManagerInternal.setIntegrityVerificationResult(
                    verificationId, PackageManagerInternal.INTEGRITY_VERIFICATION_ALLOW);
        }
    }

    /**
     * Verify the UID and return the installer package name.
     *
     * @return the package name of the installer, or null if it cannot be determined or it is
     * installed via adb.
     */
    @Nullable
    private String getInstallerPackageName(Intent intent) {
        String installer =
                intent.getStringExtra(PackageManager.EXTRA_VERIFICATION_INSTALLER_PACKAGE);
        if (installer == null) {
            return ADB_INSTALLER;
        }
        int installerUid = intent.getIntExtra(PackageManager.EXTRA_VERIFICATION_INSTALLER_UID, -1);
        if (installerUid < 0) {
            Slog.e(
                    TAG,
                    "Installer cannot be determined: installer: "
                            + installer
                            + " installer UID: "
                            + installerUid);
            return UNKNOWN_INSTALLER;
        }

        // Verify that the installer UID actually contains the package. Note that comparing UIDs
        // is not safe since context's uid can change in different settings; e.g. Android Auto.
        if (!getPackageListForUid(installerUid).contains(installer)) {
            return UNKNOWN_INSTALLER;
        }

        // At this time we can trust "installer".

        // A common way for apps to install packages is to send an intent to PackageInstaller. In
        // that case, the installer will always show up as PackageInstaller which is not what we
        // want.
        if (PACKAGE_INSTALLER.contains(installer)) {
            int originatingUid = intent.getIntExtra(EXTRA_ORIGINATING_UID, -1);
            if (originatingUid < 0) {
                Slog.e(TAG, "Installer is package installer but originating UID not found.");
                return UNKNOWN_INSTALLER;
            }
            List<String> installerPackages = getPackageListForUid(originatingUid);
            if (installerPackages.isEmpty()) {
                Slog.e(TAG, "No package found associated with originating UID " + originatingUid);
                return UNKNOWN_INSTALLER;
            }
            // In the case of multiple package sharing a UID, we just return the first one.
            return installerPackages.get(0);
        }

        return installer;
    }

    /** We will use the SHA256 digest of a package name if it is more than 32 bytes long. */
    private String getPackageNameNormalized(String packageName) {
        if (packageName.length() <= 32) {
            return packageName;
        }

        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = messageDigest.digest(packageName.getBytes(StandardCharsets.UTF_8));
            return getHexDigest(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

    private List<String> getInstallerCertificateFingerprint(String installer) {
        if (installer.equals(ADB_INSTALLER) || installer.equals(UNKNOWN_INSTALLER)) {
            return Collections.emptyList();
        }
        try {
            PackageInfo installerInfo =
                    mContext.getPackageManager()
                            .getPackageInfo(installer, PackageManager.GET_SIGNING_CERTIFICATES);
            return getCertificateFingerprint(installerInfo);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "Installer package " + installer + " not found.");
            return Collections.emptyList();
        }
    }

    private List<String> getCertificateFingerprint(@NonNull PackageInfo packageInfo) {
        ArrayList<String> certificateFingerprints = new ArrayList();
        for (Signature signature : getSignatures(packageInfo)) {
            certificateFingerprints.add(getFingerprint(signature));
        }
        return certificateFingerprints;
    }

    /** Get the allowed installers and their associated certificate hashes from <meta-data> tag. */
    private Map<String, String> getAllowedInstallers(@NonNull PackageInfo packageInfo) {
        Map<String, String> packageCertMap = new HashMap<>();
        if (packageInfo.applicationInfo != null && packageInfo.applicationInfo.metaData != null) {
            Bundle metaData = packageInfo.applicationInfo.metaData;
            String allowedInstallers = metaData.getString(ALLOWED_INSTALLERS_METADATA_NAME);
            if (allowedInstallers != null) {
                // parse the metadata for certs.
                String[] installerCertPairs = allowedInstallers.split(ALLOWED_INSTALLER_DELIMITER);
                for (String packageCertPair : installerCertPairs) {
                    String[] packageAndCert =
                            packageCertPair.split(INSTALLER_PACKAGE_CERT_DELIMITER);
                    if (packageAndCert.length == 2) {
                        String packageName = getPackageNameNormalized(packageAndCert[0]);
                        String cert = packageAndCert[1];
                        packageCertMap.put(packageName, cert);
                    } else if (packageAndCert.length == 1) {
                        packageCertMap.put(
                                getPackageNameNormalized(packageAndCert[0]),
                                INSTALLER_CERTIFICATE_NOT_EVALUATED);
                    }
                }
            }
        }

        return packageCertMap;
    }

    /** Extract the source stamp embedded in the APK, if present. */
    private void extractSourceStamp(Uri dataUri, AppInstallMetadata.Builder appInstallMetadata) {
        File installationPath = getInstallationPath(dataUri);
        if (installationPath == null) {
            throw new IllegalArgumentException("Installation path is null, package not found");
        }

        SourceStampVerificationResult sourceStampVerificationResult;
        if (installationPath.isDirectory()) {
            try (Stream<Path> filesList = Files.list(installationPath.toPath())) {
                List<String> apkFiles =
                        filesList
                                .map(path -> path.toAbsolutePath().toString())
                                .collect(Collectors.toList());
                sourceStampVerificationResult = SourceStampVerifier.verify(apkFiles);
            } catch (IOException e) {
                throw new IllegalArgumentException("Could not read APK directory");
            }
        } else {
            sourceStampVerificationResult =
                    SourceStampVerifier.verify(installationPath.getAbsolutePath());
        }

        appInstallMetadata.setIsStampPresent(sourceStampVerificationResult.isPresent());
        appInstallMetadata.setIsStampVerified(sourceStampVerificationResult.isVerified());
        // A verified stamp is set to be trusted.
        appInstallMetadata.setIsStampTrusted(sourceStampVerificationResult.isVerified());
        if (sourceStampVerificationResult.isVerified()) {
            X509Certificate sourceStampCertificate =
                    (X509Certificate) sourceStampVerificationResult.getCertificate();
            // Sets source stamp certificate digest.
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] certificateDigest = digest.digest(sourceStampCertificate.getEncoded());
                appInstallMetadata.setStampCertificateHash(getHexDigest(certificateDigest));
            } catch (NoSuchAlgorithmException | CertificateEncodingException e) {
                throw new IllegalArgumentException(
                        "Error computing source stamp certificate digest", e);
            }
        }
    }

    private static Signature[] getSignatures(@NonNull PackageInfo packageInfo) {
        SigningInfo signingInfo = packageInfo.signingInfo;

        if (signingInfo == null || signingInfo.getApkContentsSigners().length < 1) {
            throw new IllegalArgumentException("Package signature not found in " + packageInfo);
        }

        // We are only interested in evaluating the active signatures.
        return signingInfo.getApkContentsSigners();
    }

    private static String getFingerprint(Signature cert) {
        InputStream input = new ByteArrayInputStream(cert.toByteArray());

        CertificateFactory factory;
        try {
            factory = CertificateFactory.getInstance("X509");
        } catch (CertificateException e) {
            throw new RuntimeException("Error getting CertificateFactory", e);
        }
        X509Certificate certificate = null;
        try {
            if (factory != null) {
                certificate = (X509Certificate) factory.generateCertificate(input);
            }
        } catch (CertificateException e) {
            throw new RuntimeException("Error getting X509Certificate", e);
        }

        if (certificate == null) {
            throw new RuntimeException("X509 Certificate not found");
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] publicKey = digest.digest(certificate.getEncoded());
            return getHexDigest(publicKey);
        } catch (NoSuchAlgorithmException | CertificateEncodingException e) {
            throw new IllegalArgumentException("Error error computing fingerprint", e);
        }
    }

    private PackageInfo getPackageArchiveInfo(Uri dataUri) {
        File installationPath = getInstallationPath(dataUri);
        if (installationPath == null) {
            throw new IllegalArgumentException("Installation path is null, package not found");
        }

        try (PackageParser2 parser = mParserSupplier.get()) {
            ParsedPackage pkg = parser.parsePackage(installationPath, 0, false);
            int flags = PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_META_DATA;
            // APK signatures is already verified elsewhere in PackageManager. We do not need to
            // verify it again since it could cause a timeout for large APKs.
            pkg.setSigningDetails(
                    ParsingPackageUtils.getSigningDetails(pkg, /* skipVerify= */ true));
            return PackageInfoUtils.generate(
                    pkg,
                    null,
                    flags,
                    0,
                    0,
                    null,
                    new PackageUserState(),
                    UserHandle.getCallingUserId(),
                    null);
        } catch (Exception e) {
            Slog.w(TAG, "Exception reading " + dataUri, e);
            return null;
        }
    }

    private PackageInfo getMultiApkInfo(File multiApkDirectory) {
        // The base apk will normally be called base.apk
        File baseFile = new File(multiApkDirectory, BASE_APK_FILE);
        PackageInfo basePackageInfo =
                mContext.getPackageManager()
                        .getPackageArchiveInfo(
                                baseFile.getAbsolutePath(),
                                PackageManager.GET_SIGNING_CERTIFICATES
                                        | PackageManager.GET_META_DATA);

        if (basePackageInfo == null) {
            for (File apkFile : multiApkDirectory.listFiles()) {
                if (apkFile.isDirectory()) {
                    continue;
                }

                // If we didn't find a base.apk, then try to parse each apk until we find the one
                // that succeeds.
                try {
                    basePackageInfo =
                            mContext.getPackageManager()
                                    .getPackageArchiveInfo(
                                            apkFile.getAbsolutePath(),
                                            PackageManager.GET_SIGNING_CERTIFICATES
                                                    | PackageManager.GET_META_DATA);
                } catch (Exception e) {
                    // Some of the splits may not contain a valid android manifest. It is an
                    // expected exception. We still log it nonetheless but we should keep looking.
                    Slog.w(TAG, "Exception reading " + apkFile, e);
                }
                if (basePackageInfo != null) {
                    Slog.i(TAG, "Found package info from " + apkFile);
                    break;
                }
            }
        }

        if (basePackageInfo == null) {
            throw new IllegalArgumentException(
                    "Base package info cannot be found from installation directory");
        }

        return basePackageInfo;
    }

    private File getInstallationPath(Uri dataUri) {
        if (dataUri == null) {
            throw new IllegalArgumentException("Null data uri");
        }

        String scheme = dataUri.getScheme();
        if (!"file".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Unsupported scheme for " + dataUri);
        }

        File installationPath = new File(dataUri.getPath());
        if (!installationPath.exists()) {
            throw new IllegalArgumentException("Cannot find file for " + dataUri);
        }
        if (!installationPath.canRead()) {
            throw new IllegalArgumentException("Cannot read file for " + dataUri);
        }
        return installationPath;
    }

    private String getCallerPackageNameOrThrow(int callingUid) {
        String callerPackageName = getCallingRulePusherPackageName(callingUid);
        if (callerPackageName == null) {
            throw new SecurityException(
                    "Only system packages specified in config_integrityRuleProviderPackages are "
                            + "allowed to call this method.");
        }
        return callerPackageName;
    }

    private String getCallingRulePusherPackageName(int callingUid) {
        // Obtain the system apps that are whitelisted in config_integrityRuleProviderPackages.
        List<String> allowedRuleProviders = getAllowedRuleProviderSystemApps();
        if (DEBUG_INTEGRITY_COMPONENT) {
            Slog.i(
                    TAG,
                    String.format(
                            "Rule provider system app list contains: %s", allowedRuleProviders));
        }

        // Identify the package names in the caller list.
        List<String> callingPackageNames = getPackageListForUid(callingUid);

        // Find the intersection between the allowed and calling packages. Ideally, we will have
        // at most one package name here. But if we have more, it is fine.
        List<String> allowedCallingPackages = new ArrayList<>();
        for (String packageName : callingPackageNames) {
            if (allowedRuleProviders.contains(packageName)) {
                allowedCallingPackages.add(packageName);
            }
        }

        return allowedCallingPackages.isEmpty() ? null : allowedCallingPackages.get(0);
    }

    private boolean isRuleProvider(String installerPackageName) {
        for (String ruleProvider : getAllowedRuleProviderSystemApps()) {
            if (ruleProvider.matches(installerPackageName)) {
                return true;
            }
        }
        return false;
    }

    private List<String> getAllowedRuleProviderSystemApps() {
        List<String> integrityRuleProviders =
                Arrays.asList(
                        mContext.getResources()
                                .getStringArray(R.array.config_integrityRuleProviderPackages));

        // Filter out the rule provider packages that are not system apps.
        List<String> systemAppRuleProviders = new ArrayList<>();
        for (String ruleProvider : integrityRuleProviders) {
            if (isSystemApp(ruleProvider)) {
                systemAppRuleProviders.add(ruleProvider);
            }
        }
        return systemAppRuleProviders;
    }

    private boolean isSystemApp(String packageName) {
        try {
            PackageInfo existingPackageInfo =
                    mContext.getPackageManager().getPackageInfo(packageName, /* flags= */ 0);
            return existingPackageInfo.applicationInfo != null
                    && existingPackageInfo.applicationInfo.isSystemApp();
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean integrityCheckIncludesRuleProvider() {
        return Settings.Global.getInt(
                mContext.getContentResolver(),
                Settings.Global.INTEGRITY_CHECK_INCLUDES_RULE_PROVIDER,
                0)
                == 1;
    }

    private List<String> getPackageListForUid(int uid) {
        try {
            return Arrays.asList(mContext.getPackageManager().getPackagesForUid(uid));
        } catch (NullPointerException e) {
            Slog.w(TAG, String.format("No packages were found for uid: %d", uid));
            return List.of();
        }
    }
}
