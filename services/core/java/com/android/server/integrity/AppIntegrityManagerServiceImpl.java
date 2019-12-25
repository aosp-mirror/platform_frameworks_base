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
import static android.content.Intent.EXTRA_ORIGINATING_UID;
import static android.content.Intent.EXTRA_PACKAGE_NAME;
import static android.content.Intent.EXTRA_VERSION_CODE;
import static android.content.integrity.AppIntegrityManager.EXTRA_STATUS;
import static android.content.integrity.AppIntegrityManager.STATUS_FAILURE;
import static android.content.integrity.AppIntegrityManager.STATUS_SUCCESS;
import static android.content.pm.PackageManager.EXTRA_VERIFICATION_ID;

import static com.android.server.integrity.IntegrityUtils.getHexDigest;

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
import android.content.pm.ParceledListSlice;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.integrity.engine.RuleEvaluationEngine;
import com.android.server.integrity.model.IntegrityCheckResult;
import com.android.server.integrity.model.RuleMetadata;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;

/** Implementation of {@link AppIntegrityManagerService}. */
public class AppIntegrityManagerServiceImpl extends IAppIntegrityManager.Stub {
    private static final String TAG = "AppIntegrityManagerServiceImpl";

    private static final String PACKAGE_MIME_TYPE = "application/vnd.android.package-archive";
    private static final char[] HEX_CHARS = "0123456789ABCDEF".toCharArray();
    private static final String PACKAGE_INSTALLER = "com.google.android.packageinstaller";
    private static final String BASE_APK_FILE = "base.apk";
    private static final String ALLOWED_INSTALLERS_METADATA_NAME = "allowed-installers";
    private static final String ALLOWED_INSTALLER_DELIMITER = ",";
    private static final String INSTALLER_PACKAGE_CERT_DELIMITER = "\\|";

    private static final String ADB_INSTALLER = "adb";
    private static final String UNKNOWN_INSTALLER = "";
    private static final String INSTALLER_CERT_NOT_APPLICABLE = "";

    // Access to files inside mRulesDir is protected by mRulesLock;
    private final Context mContext;
    private final Handler mHandler;
    private final PackageManagerInternal mPackageManagerInternal;
    private final RuleEvaluationEngine mEvaluationEngine;
    private final IntegrityFileManager mIntegrityFileManager;

    /** Create an instance of {@link AppIntegrityManagerServiceImpl}. */
    public static AppIntegrityManagerServiceImpl create(Context context) {
        HandlerThread handlerThread = new HandlerThread("AppIntegrityManagerServiceHandler");
        handlerThread.start();

        return new AppIntegrityManagerServiceImpl(
                context,
                LocalServices.getService(PackageManagerInternal.class),
                RuleEvaluationEngine.getRuleEvaluationEngine(),
                IntegrityFileManager.getInstance(),
                handlerThread.getThreadHandler());
    }

    @VisibleForTesting
    AppIntegrityManagerServiceImpl(
            Context context,
            PackageManagerInternal packageManagerInternal,
            RuleEvaluationEngine evaluationEngine,
            IntegrityFileManager integrityFileManager,
            Handler handler) {
        mContext = context;
        mPackageManagerInternal = packageManagerInternal;
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
    public void updateRuleSet(
            String version, ParceledListSlice<Rule> rules, IntentSender statusReceiver)
            throws RemoteException {
        String ruleProvider = getCallerPackageNameOrThrow();

        mHandler.post(
                () -> {
                    boolean success = true;
                    try {
                        mIntegrityFileManager.writeRules(version, ruleProvider, rules.getList());
                    } catch (Exception e) {
                        Slog.e(TAG, "Error writing rules.", e);
                        success = false;
                    }

                    Intent intent = new Intent();
                    intent.putExtra(EXTRA_STATUS, success ? STATUS_SUCCESS : STATUS_FAILURE);
                    try {
                        statusReceiver.sendIntent(
                                mContext,
                                /* code= */ 0,
                                intent,
                                /* onFinished= */ null,
                                /* handler= */ null);
                    } catch (IntentSender.SendIntentException e) {
                        Slog.e(TAG, "Error sending status feedback.", e);
                    }
                });
    }

    @Override
    public String getCurrentRuleSetVersion() throws RemoteException {
        getCallerPackageNameOrThrow();

        RuleMetadata ruleMetadata = mIntegrityFileManager.readMetadata();
        return (ruleMetadata != null && ruleMetadata.getVersion() != null)
                ? ruleMetadata.getVersion()
                : "";
    }

    @Override
    public String getCurrentRuleSetProvider() throws RemoteException {
        getCallerPackageNameOrThrow();

        RuleMetadata ruleMetadata = mIntegrityFileManager.readMetadata();
        return (ruleMetadata != null && ruleMetadata.getRuleProvider() != null)
                ? ruleMetadata.getRuleProvider()
                : "";
    }

    private void handleIntegrityVerification(Intent intent) {
        int verificationId = intent.getIntExtra(EXTRA_VERIFICATION_ID, -1);
        try {
            Slog.i(TAG, "Received integrity verification intent " + intent.toString());
            Slog.i(TAG, "Extras " + intent.getExtras());

            String packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME);

            PackageInfo packageInfo = getPackageArchiveInfo(intent.getData());
            if (packageInfo == null) {
                Slog.w(TAG, "Cannot parse package " + packageName);
                // We can't parse the package.
                mPackageManagerInternal.setIntegrityVerificationResult(
                        verificationId, PackageManagerInternal.INTEGRITY_VERIFICATION_ALLOW);
                return;
            }

            String installerPackageName = getInstallerPackageName(intent);
            String appCert = getCertificateFingerprint(packageInfo);

            AppInstallMetadata.Builder builder = new AppInstallMetadata.Builder();

            builder.setPackageName(getPackageNameNormalized(packageName));
            builder.setAppCertificate(appCert == null ? "" : appCert);
            builder.setVersionCode(intent.getIntExtra(EXTRA_VERSION_CODE, -1));
            builder.setInstallerName(getPackageNameNormalized(installerPackageName));
            builder.setInstallerCertificate(
                    getInstallerCertificateFingerprint(installerPackageName));
            builder.setIsPreInstalled(isSystemApp(packageName));

            AppInstallMetadata appInstallMetadata = builder.build();

            Slog.i(TAG, "To be verified: " + appInstallMetadata);
            IntegrityCheckResult result =
                    mEvaluationEngine.evaluate(
                            appInstallMetadata, getAllowedInstallers(packageInfo));
            Slog.i(
                    TAG,
                    "Integrity check result: "
                            + result.getEffect()
                            + " due to "
                            + result.getRule());
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
     *     installed via adb.
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

        try {
            int actualInstallerUid =
                    mContext.getPackageManager().getPackageUid(installer, /* flags= */ 0);
            if (actualInstallerUid != installerUid) {
                // Installer package name can be faked but the installerUid cannot.
                Slog.e(
                        TAG,
                        "Installer "
                                + installer
                                + " has UID "
                                + actualInstallerUid
                                + " which doesn't match alleged installer UID "
                                + installerUid);
                return UNKNOWN_INSTALLER;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Installer package " + installer + " not found.");
            return UNKNOWN_INSTALLER;
        }

        // At this time we can trust "installer".

        // A common way for apps to install packages is to send an intent to PackageInstaller. In
        // that case, the installer will always show up as PackageInstaller which is not what we
        // want.
        if (installer.equals(PACKAGE_INSTALLER)) {
            int originatingUid = intent.getIntExtra(EXTRA_ORIGINATING_UID, -1);
            if (originatingUid < 0) {
                Slog.e(TAG, "Installer is package installer but originating UID not found.");
                return UNKNOWN_INSTALLER;
            }
            String[] installerPackages =
                    mContext.getPackageManager().getPackagesForUid(originatingUid);
            if (installerPackages == null || installerPackages.length == 0) {
                Slog.e(TAG, "No package found associated with originating UID " + originatingUid);
                return UNKNOWN_INSTALLER;
            }
            // In the case of multiple package sharing a UID, we just return the first one.
            return installerPackages[0];
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

    private String getCertificateFingerprint(@NonNull PackageInfo packageInfo) {
        return getFingerprint(getSignature(packageInfo));
    }

    private String getInstallerCertificateFingerprint(String installer) {
        if (installer.equals(ADB_INSTALLER) || installer.equals(UNKNOWN_INSTALLER)) {
            return INSTALLER_CERT_NOT_APPLICABLE;
        }
        try {
            PackageInfo installerInfo =
                    mContext.getPackageManager()
                            .getPackageInfo(installer, PackageManager.GET_SIGNATURES);
            return getCertificateFingerprint(installerInfo);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.i(TAG, "Installer package " + installer + " not found.");
            return "";
        }
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
                        String packageName = packageAndCert[0];
                        String cert = packageAndCert[1];
                        packageCertMap.put(packageName, cert);
                    }
                }
            }
        }

        Slog.i("DEBUG", "allowed installers map " + packageCertMap);
        return packageCertMap;
    }

    private boolean getPreInstalled(String packageName) {
        try {
            PackageInfo existingPackageInfo =
                    mContext.getPackageManager().getPackageInfo(packageName, 0);
            return existingPackageInfo.applicationInfo != null
                    && existingPackageInfo.applicationInfo.isSystemApp();
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static Signature getSignature(@NonNull PackageInfo packageInfo) {
        if (packageInfo.signatures == null || packageInfo.signatures.length < 1) {
            throw new IllegalArgumentException("Package signature not found in " + packageInfo);
        }
        // Only the first element is guaranteed to be present.
        return packageInfo.signatures[0];
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
        PackageInfo packageInfo;
        try {
            // The installation path will be a directory for a multi-apk install on L+
            if (installationPath.isDirectory()) {
                packageInfo = getMultiApkInfo(installationPath);
            } else {
                packageInfo =
                        mContext.getPackageManager()
                                .getPackageArchiveInfo(
                                        installationPath.getPath(),
                                        PackageManager.GET_SIGNATURES
                                                | PackageManager.GET_META_DATA);
            }
            return packageInfo;
        } catch (Exception e) {
            throw new IllegalArgumentException("Exception reading " + dataUri, e);
        }
    }

    private PackageInfo getMultiApkInfo(File multiApkDirectory) {
        // The base apk will normally be called base.apk
        File baseFile = new File(multiApkDirectory, BASE_APK_FILE);
        PackageInfo basePackageInfo =
                mContext.getPackageManager()
                        .getPackageArchiveInfo(
                                baseFile.getAbsolutePath(), PackageManager.GET_SIGNATURES);

        if (basePackageInfo == null) {
            for (File apkFile : multiApkDirectory.listFiles()) {
                if (apkFile.isDirectory()) {
                    continue;
                }

                // If we didn't find a base.apk, then try to parse each apk until we find the one
                // that succeeds.
                basePackageInfo =
                        mContext.getPackageManager()
                                .getPackageArchiveInfo(
                                        apkFile.getAbsolutePath(),
                                        PackageManager.GET_SIGNING_CERTIFICATES);
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

    private String getCallerPackageNameOrThrow() {
        final String[] allowedRuleProviders =
                mContext.getResources()
                        .getStringArray(R.array.config_integrityRuleProviderPackages);
        for (String packageName : allowedRuleProviders) {
            try {
                // At least in tests, getPackageUid gives "NameNotFound" but getPackagesFromUid
                // give the correct package name.
                int uid = mContext.getPackageManager().getPackageUid(packageName, 0);
                if (uid == Binder.getCallingUid()) {
                    // Caller is allowed in the config.
                    if (isSystemApp(packageName)) {
                        return packageName;
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                // Ignore the exception. We don't expect the app to be necessarily installed.
                Slog.i(TAG, "Rule provider package " + packageName + " not installed.");
            }
        }
        throw new SecurityException(
                "Only system packages specified in config_integrityRuleProviderPackages are"
                        + " allowed to call this method.");
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
}
