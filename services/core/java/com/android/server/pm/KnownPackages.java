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

package com.android.server.pm;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.text.TextUtils;

import com.android.internal.util.ArrayUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Helps {@link PackageManagerService} keep track of the names of special packages like SetupWizard.
 */
public final class KnownPackages {
    @IntDef(prefix = "PACKAGE_", value = {
            PACKAGE_SYSTEM,
            PACKAGE_SETUP_WIZARD,
            PACKAGE_INSTALLER,
            PACKAGE_UNINSTALLER,
            PACKAGE_VERIFIER,
            PACKAGE_BROWSER,
            PACKAGE_SYSTEM_TEXT_CLASSIFIER,
            PACKAGE_PERMISSION_CONTROLLER,
            PACKAGE_CONFIGURATOR,
            PACKAGE_INCIDENT_REPORT_APPROVER,
            PACKAGE_APP_PREDICTOR,
            PACKAGE_OVERLAY_CONFIG_SIGNATURE,
            PACKAGE_WIFI,
            PACKAGE_COMPANION,
            PACKAGE_RETAIL_DEMO,
            PACKAGE_RECENTS,
            PACKAGE_AMBIENT_CONTEXT_DETECTION,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface KnownPackage {
    }

    public static final int PACKAGE_SYSTEM = 0;
    public static final int PACKAGE_SETUP_WIZARD = 1;
    public static final int PACKAGE_INSTALLER = 2;
    public static final int PACKAGE_UNINSTALLER = 3;
    public static final int PACKAGE_VERIFIER = 4;
    public static final int PACKAGE_BROWSER = 5;
    public static final int PACKAGE_SYSTEM_TEXT_CLASSIFIER = 6;
    public static final int PACKAGE_PERMISSION_CONTROLLER = 7;
    public static final int PACKAGE_WELLBEING = 8;
    public static final int PACKAGE_DOCUMENTER = 9;
    public static final int PACKAGE_CONFIGURATOR = 10;
    public static final int PACKAGE_INCIDENT_REPORT_APPROVER = 11;
    public static final int PACKAGE_APP_PREDICTOR = 12;
    public static final int PACKAGE_OVERLAY_CONFIG_SIGNATURE = 13;
    public static final int PACKAGE_WIFI = 14;
    public static final int PACKAGE_COMPANION = 15;
    public static final int PACKAGE_RETAIL_DEMO = 16;
    public static final int PACKAGE_RECENTS = 17;
    public static final int PACKAGE_AMBIENT_CONTEXT_DETECTION = 18;
    // Integer value of the last known package ID. Increases as new ID is added to KnownPackage.
    // Please note the numbers should be continuous.
    public static final int LAST_KNOWN_PACKAGE = PACKAGE_AMBIENT_CONTEXT_DETECTION;

    private final DefaultAppProvider mDefaultAppProvider;
    private final String mRequiredInstallerPackage;
    private final String mRequiredUninstallerPackage;
    private final String mSetupWizardPackage;
    private final String mRequiredVerifierPackage;
    private final String mDefaultTextClassifierPackage;
    private final String mSystemTextClassifierPackageName;
    private final String mRequiredPermissionControllerPackage;
    private final String mConfiguratorPackage;
    private final String mIncidentReportApproverPackage;
    private final String mAmbientContextDetectionPackage;
    private final String mAppPredictionServicePackage;
    private final String mCompanionPackage;
    private final String mRetailDemoPackage;
    private final String mOverlayConfigSignaturePackage;
    private final String mRecentsPackage;

    KnownPackages(DefaultAppProvider defaultAppProvider, String requiredInstallerPackage,
            String requiredUninstallerPackage, String setupWizardPackage,
            String requiredVerifierPackage, String defaultTextClassifierPackage,
            String systemTextClassifierPackageName, String requiredPermissionControllerPackage,
            String configuratorPackage, String incidentReportApproverPackage,
            String ambientContextDetectionPackage, String appPredictionServicePackage,
            String companionPackageName, String retailDemoPackage,
            String overlayConfigSignaturePackage, String recentsPackage) {
        mDefaultAppProvider = defaultAppProvider;
        mRequiredInstallerPackage = requiredInstallerPackage;
        mRequiredUninstallerPackage = requiredUninstallerPackage;
        mSetupWizardPackage = setupWizardPackage;
        mRequiredVerifierPackage = requiredVerifierPackage;
        mDefaultTextClassifierPackage = defaultTextClassifierPackage;
        mSystemTextClassifierPackageName = systemTextClassifierPackageName;
        mRequiredPermissionControllerPackage = requiredPermissionControllerPackage;
        mConfiguratorPackage = configuratorPackage;
        mIncidentReportApproverPackage = incidentReportApproverPackage;
        mAmbientContextDetectionPackage = ambientContextDetectionPackage;
        mAppPredictionServicePackage = appPredictionServicePackage;
        mCompanionPackage = companionPackageName;
        mRetailDemoPackage = retailDemoPackage;
        mOverlayConfigSignaturePackage = overlayConfigSignaturePackage;
        mRecentsPackage = recentsPackage;
    }

    /**
     * Returns the string representation of a known package. For example,
     * {@link #PACKAGE_SETUP_WIZARD} is represented by the string Setup Wizard.
     *
     * @param knownPackage The known package.
     * @return The string representation.
     */
    static @NonNull String knownPackageToString(@KnownPackage int knownPackage) {
        switch (knownPackage) {
            case PACKAGE_SYSTEM:
                return "System";
            case PACKAGE_SETUP_WIZARD:
                return "Setup Wizard";
            case PACKAGE_INSTALLER:
                return "Installer";
            case PACKAGE_UNINSTALLER:
                return "Uninstaller";
            case PACKAGE_VERIFIER:
                return "Verifier";
            case PACKAGE_BROWSER:
                return "Browser";
            case PACKAGE_SYSTEM_TEXT_CLASSIFIER:
                return "System Text Classifier";
            case PACKAGE_PERMISSION_CONTROLLER:
                return "Permission Controller";
            case PACKAGE_WELLBEING:
                return "Wellbeing";
            case PACKAGE_DOCUMENTER:
                return "Documenter";
            case PACKAGE_CONFIGURATOR:
                return "Configurator";
            case PACKAGE_INCIDENT_REPORT_APPROVER:
                return "Incident Report Approver";
            case PACKAGE_APP_PREDICTOR:
                return "App Predictor";
            case PACKAGE_WIFI:
                return "Wi-Fi";
            case PACKAGE_COMPANION:
                return "Companion";
            case PACKAGE_RETAIL_DEMO:
                return "Retail Demo";
            case PACKAGE_OVERLAY_CONFIG_SIGNATURE:
                return "Overlay Config Signature";
            case PACKAGE_RECENTS:
                return "Recents";
            case PACKAGE_AMBIENT_CONTEXT_DETECTION:
                return "Ambient Context Detection";
        }
        return "Unknown";
    }

    String[] getKnownPackageNames(@NonNull Computer snapshot, int knownPackage, int userId) {
        switch (knownPackage) {
            case PACKAGE_BROWSER:
                return new String[]{mDefaultAppProvider.getDefaultBrowser(userId)};
            case PACKAGE_INSTALLER:
                return snapshot.filterOnlySystemPackages(mRequiredInstallerPackage);
            case PACKAGE_UNINSTALLER:
                return snapshot.filterOnlySystemPackages(mRequiredUninstallerPackage);
            case PACKAGE_SETUP_WIZARD:
                return snapshot.filterOnlySystemPackages(mSetupWizardPackage);
            case PACKAGE_SYSTEM:
                return new String[]{"android"};
            case PACKAGE_VERIFIER:
                return snapshot.filterOnlySystemPackages(mRequiredVerifierPackage);
            case PACKAGE_SYSTEM_TEXT_CLASSIFIER:
                return snapshot.filterOnlySystemPackages(
                        mDefaultTextClassifierPackage, mSystemTextClassifierPackageName);
            case PACKAGE_PERMISSION_CONTROLLER:
                return snapshot.filterOnlySystemPackages(mRequiredPermissionControllerPackage);
            case PACKAGE_CONFIGURATOR:
                return snapshot.filterOnlySystemPackages(mConfiguratorPackage);
            case PACKAGE_INCIDENT_REPORT_APPROVER:
                return snapshot.filterOnlySystemPackages(mIncidentReportApproverPackage);
            case PACKAGE_AMBIENT_CONTEXT_DETECTION:
                return snapshot.filterOnlySystemPackages(mAmbientContextDetectionPackage);
            case PACKAGE_APP_PREDICTOR:
                return snapshot.filterOnlySystemPackages(mAppPredictionServicePackage);
            case PACKAGE_COMPANION:
                return snapshot.filterOnlySystemPackages(mCompanionPackage);
            case PACKAGE_RETAIL_DEMO:
                return TextUtils.isEmpty(mRetailDemoPackage)
                        ? ArrayUtils.emptyArray(String.class)
                        : new String[]{mRetailDemoPackage};
            case PACKAGE_OVERLAY_CONFIG_SIGNATURE:
                return snapshot.filterOnlySystemPackages(mOverlayConfigSignaturePackage);
            case PACKAGE_RECENTS:
                return snapshot.filterOnlySystemPackages(mRecentsPackage);
            default:
                return ArrayUtils.emptyArray(String.class);
        }
    }
}
