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

package com.android.server.security;

import static android.security.attestationverification.AttestationVerificationManager.PARAM_CHALLENGE;
import static android.security.attestationverification.AttestationVerificationManager.PARAM_PUBLIC_KEY;
import static android.security.attestationverification.AttestationVerificationManager.RESULT_FAILURE;
import static android.security.attestationverification.AttestationVerificationManager.RESULT_SUCCESS;
import static android.security.attestationverification.AttestationVerificationManager.TYPE_CHALLENGE;
import static android.security.attestationverification.AttestationVerificationManager.TYPE_PUBLIC_KEY;
import static android.security.attestationverification.AttestationVerificationManager.localBindingTypeToString;

import static com.android.server.security.AndroidKeystoreAttestationVerificationAttributes.VerifiedBootState.VERIFIED;
import static com.android.server.security.AndroidKeystoreAttestationVerificationAttributes.fromCertificate;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.security.attestationverification.AttestationVerificationManager;
import android.security.attestationverification.AttestationVerificationManager.LocalBindingType;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.security.AttestationVerificationManagerService.DumpLogger;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.InvalidAlgorithmParameterException;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXCertPathChecker;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Verifies Android key attestation according to the
 * {@link
 * android.security.attestationverification.AttestationVerificationManager#PROFILE_PEER_DEVICE
 * PROFILE_PEER_DEVICE}
 * profile.
 *
 * <p>
 * The profile is satisfied by checking all the following:
 * <ul>
 * <li> TrustAnchor match
 * <li> Certificate validity
 * <li> Android OS 10 or higher
 * <li> Hardware backed key store
 * <li> Verified boot locked
 * <li> Remote Patch level must be within 1 year of local patch if local patch is less than 1 year
 * old.
 * </ul>
 *
 * <p>
 * Trust anchors are vendor-defined by populating
 * {@link R.array#vendor_required_attestation_certificates} string array (defenined in
 * {@code frameworks/base/core/res/res/values/vendor_required_attestation_certificates.xml}).
 */
class AttestationVerificationPeerDeviceVerifier {
    private static final String TAG = "AVF";
    private static final boolean DEBUG = Build.IS_DEBUGGABLE && Log.isLoggable(TAG, Log.VERBOSE);
    private static final int MAX_PATCH_AGE_MONTHS = 12;

    /**
     * Optional requirements bundle parameter key for {@code TYPE_PUBLIC_KEY} and
     * {@code TYPE_CHALLENGE}.
     *
     * <p>
     * This is NOT a part of the AVF API surface (neither public SDK nor internal to the
     * system_server) and should really only be used by the CompanionDeviceManagerService (which
     * duplicates the value rather than referencing it directly here).
     */
    private static final String PARAM_OWNED_BY_SYSTEM = "android.key_owned_by_system";

    private static final String ANDROID_SYSTEM_PACKAGE_NAME = "AndroidSystem";
    private static final Set<String> ANDROID_SYSTEM_PACKAGE_NAME_SET =
            Collections.singleton(ANDROID_SYSTEM_PACKAGE_NAME);

    private final Context mContext;
    private final Set<TrustAnchor> mTrustAnchors;
    private final boolean mRevocationEnabled;
    private final LocalDate mTestSystemDate;
    private final LocalDate mTestLocalPatchDate;
    private final CertificateFactory mCertificateFactory;
    private final CertPathValidator mCertPathValidator;
    private final DumpLogger mDumpLogger;

    AttestationVerificationPeerDeviceVerifier(@NonNull Context context,
            @NonNull DumpLogger dumpLogger) throws Exception {
        mContext = Objects.requireNonNull(context);
        mDumpLogger = dumpLogger;
        mCertificateFactory = CertificateFactory.getInstance("X.509");
        mCertPathValidator = CertPathValidator.getInstance("PKIX");
        mTrustAnchors = getTrustAnchors();
        mRevocationEnabled = true;
        mTestSystemDate = null;
        mTestLocalPatchDate = null;
    }

    // Use ONLY for hermetic unit testing.
    @VisibleForTesting
    AttestationVerificationPeerDeviceVerifier(@NonNull Context context,
            DumpLogger dumpLogger, Set<TrustAnchor> trustAnchors, boolean revocationEnabled,
            LocalDate systemDate, LocalDate localPatchDate) throws Exception {
        mContext = Objects.requireNonNull(context);
        mDumpLogger = dumpLogger;
        mCertificateFactory = CertificateFactory.getInstance("X.509");
        mCertPathValidator = CertPathValidator.getInstance("PKIX");
        mTrustAnchors = trustAnchors;
        mRevocationEnabled = revocationEnabled;
        mTestSystemDate = systemDate;
        mTestLocalPatchDate = localPatchDate;
    }

    /**
     * Verifies attestation for public key or challenge local binding.
     * <p>
     * The attestations must be suitable for {@link java.security.cert.CertificateFactory}
     * The certificates in the attestation provided must be DER-encoded and may be supplied in
     * binary or printable (Base64) encoding. If the certificate is provided in Base64 encoding,
     * it must be bounded at the beginning by {@code -----BEGIN CERTIFICATE-----}, and must be
     * bounded at the end by {@code -----END CERTIFICATE-----}.
     *
     * @param localBindingType Only {@code TYPE_PUBLIC_KEY} and {@code TYPE_CHALLENGE} supported.
     * @param requirements     Only {@code PARAM_PUBLIC_KEY} and {@code PARAM_CHALLENGE} supported.
     * @param attestation      Certificates should be DER encoded with leaf certificate appended
     *                         first.
     */
    int verifyAttestation(
            @LocalBindingType int localBindingType,
            @NonNull Bundle requirements,
            @NonNull byte[] attestation) {

        MyDumpData dumpData = new MyDumpData();

        int result =
                verifyAttestationInternal(localBindingType, requirements, attestation, dumpData);
        dumpData.mResult = result;
        mDumpLogger.logAttempt(dumpData);
        return result;
    }

    private int verifyAttestationInternal(
            @LocalBindingType int localBindingType,
            @NonNull Bundle requirements,
            @NonNull byte[] attestation,
            @NonNull MyDumpData dumpData) {
        if (mCertificateFactory == null) {
            debugVerboseLog("Unable to access CertificateFactory");
            return RESULT_FAILURE;
        }
        dumpData.mCertificationFactoryAvailable = true;

        if (mCertPathValidator == null) {
            debugVerboseLog("Unable to access CertPathValidator");
            return RESULT_FAILURE;
        }
        dumpData.mCertPathValidatorAvailable = true;


        // Check if the provided local binding type is supported and if the provided requirements
        // "match" the binding type.
        if (!validateAttestationParameters(localBindingType, requirements)) {
            return RESULT_FAILURE;
        }
        dumpData.mAttestationParametersOk = true;

        // To provide the most information in the dump logs, we track the failure state but keep
        // verifying the rest of the attestation. For code safety, there are no transitions past
        // here to set failed = false
        boolean failed = false;

        try {
            // First: parse and validate the certificate chain.
            final List<X509Certificate> certificateChain = getCertificates(attestation);
            // (returns void, but throws CertificateException and other similar Exceptions)
            validateCertificateChain(certificateChain);
            dumpData.mCertChainOk = true;

            final var leafCertificate = certificateChain.get(0);
            final var attestationExtension = fromCertificate(leafCertificate);

            // Second: verify if the attestation satisfies the "peer device" profile.
            if (!checkAttestationForPeerDeviceProfile(attestationExtension, dumpData)) {
                failed = true;
            }

            // Third: check if the attestation satisfies local binding requirements.
            if (!checkLocalBindingRequirements(
                    leafCertificate, attestationExtension, localBindingType, requirements,
                    dumpData)) {
                failed = true;
            }
        } catch (CertificateException | CertPathValidatorException
                 | InvalidAlgorithmParameterException | IOException e) {
            // Catch all non-RuntimeExpceptions (all of these are thrown by either getCertificates()
            // or validateCertificateChain() or
            // AndroidKeystoreAttestationVerificationAttributes.fromCertificate())
            debugVerboseLog("Unable to parse/validate Android Attestation certificate(s)", e);
            failed = true;
        } catch (RuntimeException e) {
            // Catch everyting else (RuntimeExpcetions), since we don't want to throw any exceptions
            // out of this class/method.
            debugVerboseLog("Unexpected error", e);
            failed = true;
        }

        return failed ? RESULT_FAILURE : RESULT_SUCCESS;
    }

    @NonNull
    private List<X509Certificate> getCertificates(byte[] attestation)
            throws CertificateException {
        List<X509Certificate> certificates = new ArrayList<>();
        ByteArrayInputStream bis = new ByteArrayInputStream(attestation);
        while (bis.available() > 0) {
            certificates.add((X509Certificate) mCertificateFactory.generateCertificate(bis));
        }

        return certificates;
    }

    /**
     * Check if the {@code localBindingType} is supported and if the {@code requirements} contains
     * the required parameter for the given {@code @LocalBindingType}.
     */
    private boolean validateAttestationParameters(
            @LocalBindingType int localBindingType, @NonNull Bundle requirements) {
        if (localBindingType != TYPE_PUBLIC_KEY && localBindingType != TYPE_CHALLENGE) {
            debugVerboseLog("Binding type is not supported: " + localBindingType);
            return false;
        }

        if (requirements.size() < 1) {
            debugVerboseLog("At least 1 requirement is required.");
            return false;
        }

        if (localBindingType == TYPE_PUBLIC_KEY && !requirements.containsKey(PARAM_PUBLIC_KEY)) {
            debugVerboseLog("Requirements does not contain key: " + PARAM_PUBLIC_KEY);
            return false;
        }

        if (localBindingType == TYPE_CHALLENGE && !requirements.containsKey(PARAM_CHALLENGE)) {
            debugVerboseLog("Requirements does not contain key: " + PARAM_CHALLENGE);
            return false;
        }

        return true;
    }

    private void validateCertificateChain(List<X509Certificate> certificates)
            throws CertificateException, CertPathValidatorException,
            InvalidAlgorithmParameterException {
        if (certificates.size() < 2) {
            debugVerboseLog("Certificate chain less than 2 in size.");
            throw new CertificateException("Certificate chain less than 2 in size.");
        }

        CertPath certificatePath = mCertificateFactory.generateCertPath(certificates);
        PKIXParameters validationParams = new PKIXParameters(mTrustAnchors);
        if (mRevocationEnabled) {
            // Checks Revocation Status List based on
            // https://developer.android.com/training/articles/security-key-attestation#certificate_status
            PKIXCertPathChecker checker = new AndroidRevocationStatusListChecker();
            validationParams.addCertPathChecker(checker);
        }
        // Do not use built-in revocation status checker.
        validationParams.setRevocationEnabled(false);
        mCertPathValidator.validate(certificatePath, validationParams);
    }

    private Set<TrustAnchor> getTrustAnchors() throws CertPathValidatorException {
        Set<TrustAnchor> modifiableSet = new HashSet<>();
        try {
            for (String certString : getTrustAnchorResources()) {
                modifiableSet.add(
                        new TrustAnchor((X509Certificate) mCertificateFactory.generateCertificate(
                                new ByteArrayInputStream(getCertificateBytes(certString))), null));
            }
        } catch (CertificateException e) {
            e.printStackTrace();
            throw new CertPathValidatorException("Invalid trust anchor certificate.", e);
        }
        return Collections.unmodifiableSet(modifiableSet);
    }

    private byte[] getCertificateBytes(String certString) {
        String formattedCertString = certString.replaceAll("\\s+", "\n");
        formattedCertString = formattedCertString.replaceAll(
                "-BEGIN\\nCERTIFICATE-", "-BEGIN CERTIFICATE-");
        formattedCertString = formattedCertString.replaceAll(
                "-END\\nCERTIFICATE-", "-END CERTIFICATE-");
        return formattedCertString.getBytes(UTF_8);
    }

    private String[] getTrustAnchorResources() {
        return mContext.getResources().getStringArray(
                R.array.vendor_required_attestation_certificates);
    }

    private boolean checkLocalBindingRequirements(
            @NonNull X509Certificate leafCertificate,
            @NonNull AndroidKeystoreAttestationVerificationAttributes attestationAttributes,
            @LocalBindingType int localBindingType,
            @NonNull Bundle requirements, MyDumpData dumpData) {
        // First: check non-optional (for the given local binding type) requirements.
        dumpData.mBindingType = localBindingType;
        switch (localBindingType) {
            case TYPE_PUBLIC_KEY:
                // Verify leaf public key matches provided public key.
                final boolean publicKeyMatches = checkPublicKey(
                        leafCertificate, requirements.getByteArray(PARAM_PUBLIC_KEY));
                if (!publicKeyMatches) {
                    debugVerboseLog(
                            "Provided public key does not match leaf certificate public key.");
                    return false;
                }
                break;

            case TYPE_CHALLENGE:
                // Verify challenge matches provided challenge.
                final boolean attestationChallengeMatches = checkAttestationChallenge(
                        attestationAttributes, requirements.getByteArray(PARAM_CHALLENGE));
                if (!attestationChallengeMatches) {
                    debugVerboseLog(
                            "Provided challenge does not match leaf certificate challenge.");
                    return false;
                }
                break;

            default:
                throw new IllegalArgumentException("Unsupported local binding type "
                        + localBindingTypeToString(localBindingType));
        }
        dumpData.mBindingOk = true;

        // Second: check specified optional requirements.
        if (requirements.containsKey(PARAM_OWNED_BY_SYSTEM)) {
            dumpData.mSystemOwnershipChecked = true;
            if (requirements.getBoolean(PARAM_OWNED_BY_SYSTEM)) {
                // Verify key is owned by the system.
                final boolean ownedBySystem = checkOwnedBySystem(
                        leafCertificate, attestationAttributes);
                if (!ownedBySystem) {
                    debugVerboseLog("Certificate public key is not owned by the AndroidSystem.");
                    return false;
                }
                dumpData.mSystemOwned = true;
            } else {
                throw new IllegalArgumentException("The value of the requirement key "
                        + PARAM_OWNED_BY_SYSTEM
                        + " cannot be false. You can remove the key if you don't want to verify "
                        + "it.");
            }
        }

        return true;
    }

    private boolean checkAttestationForPeerDeviceProfile(
            @NonNull AndroidKeystoreAttestationVerificationAttributes attestationAttributes,
            MyDumpData dumpData) {
        boolean result = true;

        // Checks for support of Keymaster 4.
        if (attestationAttributes.getAttestationVersion() < 3) {
            debugVerboseLog("Attestation version is not at least 3 (Keymaster 4).");
            result = false;
        } else {
            dumpData.mAttestationVersionAtLeast3 = true;
        }

        // Checks for support of Keymaster 4.
        if (attestationAttributes.getKeymasterVersion() < 4) {
            debugVerboseLog("Keymaster version is not at least 4.");
            result = false;
        } else {
            dumpData.mKeymasterVersionAtLeast4 = true;
        }

        // First two characters are Android OS version.
        if (attestationAttributes.getKeyOsVersion() < 100000) {
            debugVerboseLog("Android OS version is not 10+.");
            result = false;
        } else {
            dumpData.mOsVersionAtLeast10 = true;
        }

        if (!attestationAttributes.isAttestationHardwareBacked()) {
            debugVerboseLog("Key is not HW backed.");
            result = false;
        } else {
            dumpData.mKeyHwBacked = true;
        }

        if (!attestationAttributes.isKeymasterHardwareBacked()) {
            debugVerboseLog("Keymaster is not HW backed.");
            result = false;
        } else {
            dumpData.mKeymasterHwBacked = true;
        }

        if (attestationAttributes.getVerifiedBootState() != VERIFIED) {
            debugVerboseLog("Boot state not Verified.");
            result = false;
        } else {
            dumpData.mBootStateIsVerified = true;
        }

        try {
            if (!attestationAttributes.isVerifiedBootLocked()) {
                debugVerboseLog("Verified boot state is not locked.");
                result = false;
            } else {
                dumpData.mVerifiedBootStateLocked = true;
            }
        } catch (IllegalStateException e) {
            debugVerboseLog("VerifiedBootLocked is not set.", e);
            result = false;
        }

        // Patch level integer YYYYMM is expected to be within 1 year of today.
        if (!isValidPatchLevel(attestationAttributes.getKeyOsPatchLevel())) {
            debugVerboseLog("OS patch level is not within valid range.");
            result = false;
        } else {
            dumpData.mOsPatchLevelInRange = true;
        }

        // Patch level integer YYYYMMDD is expected to be within 1 year of today.
        if (!isValidPatchLevel(attestationAttributes.getKeyBootPatchLevel())) {
            debugVerboseLog("Boot patch level is not within valid range.");
            result = false;
        } else {
            dumpData.mKeyBootPatchLevelInRange = true;
        }

        if (!isValidPatchLevel(attestationAttributes.getKeyVendorPatchLevel())) {
            debugVerboseLog("Vendor patch level is not within valid range.");
            result = false;
        } else {
            dumpData.mKeyVendorPatchLevelInRange = true;
        }

        if (!isValidPatchLevel(attestationAttributes.getKeyBootPatchLevel())) {
            debugVerboseLog("Boot patch level is not within valid range.");
            result = false;
        } else {
            dumpData.mKeyBootPatchLevelInRange = true;
        }

        return result;
    }

    private boolean checkPublicKey(
            @NonNull Certificate certificate, @NonNull byte[] expectedPublicKey) {
        final byte[] publicKey = certificate.getPublicKey().getEncoded();
        return Arrays.equals(publicKey, expectedPublicKey);
    }

    private boolean checkAttestationChallenge(
            @NonNull AndroidKeystoreAttestationVerificationAttributes attestationAttributes,
            @NonNull byte[] expectedChallenge) {
        final byte[] challenge = attestationAttributes.getAttestationChallenge().toByteArray();
        return Arrays.equals(challenge, expectedChallenge);
    }

    private boolean checkOwnedBySystem(@NonNull X509Certificate certificate,
            @NonNull AndroidKeystoreAttestationVerificationAttributes attestationAttributes) {
        final Set<String> ownerPackages =
                attestationAttributes.getApplicationPackageNameVersion().keySet();
        if (!ANDROID_SYSTEM_PACKAGE_NAME_SET.equals(ownerPackages)) {
            debugVerboseLog("Owner is not system, packages=" + ownerPackages);
            return false;
        }

        return true;
    }

    /**
     * Validates patchLevel passed is within range of the local device patch date if local patch is
     * not over one year old. Since the time can be changed on device, just checking the patch date
     * is not enough. Therefore, we also confirm the patch level for the remote and local device are
     * similar.
     */
    private boolean isValidPatchLevel(int patchLevel) {
        LocalDate currentDate = mTestSystemDate != null
                ? mTestSystemDate : LocalDate.now(ZoneId.systemDefault());

        // Convert local patch date to LocalDate.
        LocalDate localPatchDate;
        try {
            if (mTestLocalPatchDate != null) {
                localPatchDate = mTestLocalPatchDate;
            } else {
                localPatchDate = LocalDate.parse(Build.VERSION.SECURITY_PATCH);
            }
        } catch (Throwable t) {
            debugVerboseLog("Build.VERSION.SECURITY_PATCH: "
                    + Build.VERSION.SECURITY_PATCH + " is not in format YYYY-MM-DD");
            return false;
        }

        // Check local patch date is not in last year of system clock.
        if (ChronoUnit.MONTHS.between(localPatchDate, currentDate) > MAX_PATCH_AGE_MONTHS) {
            return true;
        }

        // Convert remote patch dates to LocalDate.
        String remoteDeviceDateStr = String.valueOf(patchLevel);
        if (remoteDeviceDateStr.length() != 6 && remoteDeviceDateStr.length() != 8) {
            debugVerboseLog("Patch level is not in format YYYYMM or YYYYMMDD");
            return false;
        }

        int patchYear = Integer.parseInt(remoteDeviceDateStr.substring(0, 4));
        int patchMonth = Integer.parseInt(remoteDeviceDateStr.substring(4, 6));
        LocalDate remotePatchDate = LocalDate.of(patchYear, patchMonth, 1);

        // Check patch dates are within 1 year of each other
        boolean IsRemotePatchWithinOneYearOfLocalPatch;
        if (remotePatchDate.compareTo(localPatchDate) > 0) {
            IsRemotePatchWithinOneYearOfLocalPatch = ChronoUnit.MONTHS.between(
                    localPatchDate, remotePatchDate) <= MAX_PATCH_AGE_MONTHS;
        } else if (remotePatchDate.compareTo(localPatchDate) < 0) {
            IsRemotePatchWithinOneYearOfLocalPatch = ChronoUnit.MONTHS.between(
                    remotePatchDate, localPatchDate) <= MAX_PATCH_AGE_MONTHS;
        } else {
            IsRemotePatchWithinOneYearOfLocalPatch = true;
        }

        return IsRemotePatchWithinOneYearOfLocalPatch;
    }

    /**
     * Checks certificate revocation status.
     *
     * Queries status list from android.googleapis.com/attestation/status and checks for
     * the existence of certificate's serial number. If serial number exists in map, then fail.
     */
    private final class AndroidRevocationStatusListChecker extends PKIXCertPathChecker {
        private static final String TOP_LEVEL_JSON_PROPERTY_KEY = "entries";
        private static final String STATUS_PROPERTY_KEY = "status";
        private static final String REASON_PROPERTY_KEY = "reason";
        private String mStatusUrl;
        private JSONObject mJsonStatusMap;

        @Override
        public void init(boolean forward) throws CertPathValidatorException {
            mStatusUrl = getRevocationListUrl();
            if (mStatusUrl == null || mStatusUrl.isEmpty()) {
                throw new CertPathValidatorException(
                        "R.string.vendor_required_attestation_revocation_list_url is empty.");
            }
            // TODO(b/221067843): Update to only pull status map on non critical path and if
            // out of date (24hrs).
            mJsonStatusMap = getStatusMap(mStatusUrl);
        }

        @Override
        public boolean isForwardCheckingSupported() {
            return false;
        }

        @Override
        public Set<String> getSupportedExtensions() {
            return null;
        }

        @Override
        public void check(Certificate cert, Collection<String> unresolvedCritExts)
                throws CertPathValidatorException {
            X509Certificate x509Certificate = (X509Certificate) cert;
            // The json key is the certificate's serial number converted to lowercase hex.
            String serialNumber = x509Certificate.getSerialNumber().toString(16);

            if (serialNumber == null) {
                throw new CertPathValidatorException("Certificate serial number can not be null.");
            }

            if (mJsonStatusMap.has(serialNumber)) {
                JSONObject revocationStatus;
                String status;
                String reason;
                try {
                    revocationStatus = mJsonStatusMap.getJSONObject(serialNumber);
                    status = revocationStatus.getString(STATUS_PROPERTY_KEY);
                    reason = revocationStatus.getString(REASON_PROPERTY_KEY);
                } catch (Throwable t) {
                    throw new CertPathValidatorException("Unable get properties for certificate "
                            + "with serial number " + serialNumber);
                }
                throw new CertPathValidatorException(
                        "Invalid certificate with serial number " + serialNumber
                                + " has status " + status
                                + " because reason " + reason);
            }
        }

        private JSONObject getStatusMap(String stringUrl) throws CertPathValidatorException {
            URL url;
            try {
                url = new URL(stringUrl);
            } catch (Throwable t) {
                throw new CertPathValidatorException(
                        "Unable to get revocation status from " + mStatusUrl, t);
            }

            try (InputStream inputStream = url.openStream()) {
                JSONObject statusListJson = new JSONObject(
                        new String(inputStream.readAllBytes(), UTF_8));
                return statusListJson.getJSONObject(TOP_LEVEL_JSON_PROPERTY_KEY);
            } catch (Throwable t) {
                throw new CertPathValidatorException(
                        "Unable to parse revocation status from " + mStatusUrl, t);
            }
        }

        private String getRevocationListUrl() {
            return mContext.getResources().getString(
                    R.string.vendor_required_attestation_revocation_list_url);
        }
    }

    private static void debugVerboseLog(String str, Throwable t) {
        if (DEBUG) {
            Slog.v(TAG, str, t);
        }
    }

    private static void debugVerboseLog(String str) {
        if (DEBUG) {
            Slog.v(TAG, str);
        }
    }

    /* Mutable data class for tracking dump data from verifications. */
    private static class MyDumpData extends AttestationVerificationManagerService.DumpData {

        // Top-Level Result
        int mResult = -1;

        // Configuration/Setup preconditions
        boolean mCertificationFactoryAvailable = false;
        boolean mCertPathValidatorAvailable = false;

        // AttestationParameters (Valid Input Only)
        boolean mAttestationParametersOk = false;

        // Certificate Chain (Structure & Chaining Conditions)
        boolean mCertChainOk = false;

        // Binding
        boolean mBindingOk = false;
        int mBindingType = -1;

        // System Ownership
        boolean mSystemOwnershipChecked = false;
        boolean mSystemOwned = false;

        // Android Keystore attestation properties
        boolean mOsVersionAtLeast10 = false;
        boolean mKeyHwBacked = false;
        boolean mAttestationVersionAtLeast3 = false;
        boolean mKeymasterVersionAtLeast4 = false;
        boolean mKeymasterHwBacked = false;
        boolean mBootStateIsVerified = false;
        boolean mVerifiedBootStateLocked = false;
        boolean mOsPatchLevelInRange = false;
        boolean mKeyBootPatchLevelInRange = false;
        boolean mKeyVendorPatchLevelInRange = false;

        @SuppressLint("WrongConstant")
        @Override
        public void dumpTo(IndentingPrintWriter writer) {
            writer.println(
                    "Result: " + AttestationVerificationManager.verificationResultCodeToString(
                            mResult));
            if (!mCertificationFactoryAvailable) {
                writer.println("Certificate Factory Unavailable");
                return;
            }
            if (!mCertPathValidatorAvailable) {
                writer.println("Cert Path Validator Unavailable");
                return;
            }
            if (!mAttestationParametersOk) {
                writer.println("Attestation parameters set incorrectly.");
                return;
            }

            writer.println("Certificate Chain Valid (inc. Trust Anchor): " + booleanToOkFail(
                    mCertChainOk));
            if (!mCertChainOk) {
                return;
            }

            // Binding
            writer.println("Local Binding: " + booleanToOkFail(mBindingOk));
            writer.increaseIndent();
            writer.println("Binding Type: " + mBindingType);
            writer.decreaseIndent();

            if (mSystemOwnershipChecked) {
                writer.println("System Ownership: " + booleanToOkFail(mSystemOwned));
            }

            // Keystore Attestation params
            writer.println("KeyStore Attestation Parameters");
            writer.increaseIndent();
            writer.println("OS Version >= 10: " + booleanToOkFail(mOsVersionAtLeast10));
            writer.println("OS Patch Level in Range: " + booleanToOkFail(mOsPatchLevelInRange));
            writer.println(
                    "Attestation Version >= 3: " + booleanToOkFail(mAttestationVersionAtLeast3));
            writer.println("Keymaster Version >= 4: " + booleanToOkFail(mKeymasterVersionAtLeast4));
            writer.println("Keymaster HW-Backed: " + booleanToOkFail(mKeymasterHwBacked));
            writer.println("Key is HW Backed: " + booleanToOkFail(mKeyHwBacked));
            writer.println("Boot State is VERIFIED: " + booleanToOkFail(mBootStateIsVerified));
            writer.println("Verified Boot is LOCKED: " + booleanToOkFail(mVerifiedBootStateLocked));
            writer.println(
                    "Key Boot Level in Range: " + booleanToOkFail(mKeyBootPatchLevelInRange));
            writer.println("Key Vendor Patch Level in Range: " + booleanToOkFail(
                    mKeyVendorPatchLevelInRange));
            writer.decreaseIndent();
        }

        private String booleanToOkFail(boolean value) {
            return value ? "OK" : "FAILURE";
        }
    }
}
