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
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.security.attestationverification.AttestationVerificationManager.LocalBindingType;
import android.util.Log;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

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
 * {@link android.security.attestationverification.AttestationVerificationManager#PROFILE_PEER_DEVICE PROFILE_PEER_DEVICE}
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

    private final Context mContext;
    private final Set<TrustAnchor> mTrustAnchors;
    private final boolean mRevocationEnabled;
    private final LocalDate mTestSystemDate;
    private final LocalDate mTestLocalPatchDate;
    private final CertificateFactory mCertificateFactory;
    private final CertPathValidator mCertPathValidator;

    AttestationVerificationPeerDeviceVerifier(@NonNull Context context) throws Exception {
        mContext = Objects.requireNonNull(context);
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
            Set<TrustAnchor> trustAnchors, boolean revocationEnabled,
            LocalDate systemDate, LocalDate localPatchDate) throws Exception {
        mContext = Objects.requireNonNull(context);
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
     * @param requirements Only {@code PARAM_PUBLIC_KEY} and {@code PARAM_CHALLENGE} supported.
     * @param attestation Certificates should be DER encoded with leaf certificate appended first.
     */
    int verifyAttestation(
            @LocalBindingType int localBindingType,
            @NonNull Bundle requirements,
            @NonNull byte[] attestation) {
        if (mCertificateFactory == null) {
            debugVerboseLog("Unable to access CertificateFactory");
            return RESULT_FAILURE;
        }

        if (mCertPathValidator == null) {
            debugVerboseLog("Unable to access CertPathValidator");
            return RESULT_FAILURE;
        }

        // Check if the provided local binding type is supported and if the provided requirements
        // "match" the binding type.
        if (!validateAttestationParameters(localBindingType, requirements)) {
            return RESULT_FAILURE;
        }

        try {
            // First: parse and validate the certificate chain.
            final List<X509Certificate> certificateChain = getCertificates(attestation);
            // (returns void, but throws CertificateException and other similar Exceptions)
            validateCertificateChain(certificateChain);

            final var leafCertificate = certificateChain.get(0);
            final var attestationExtension = fromCertificate(leafCertificate);

            // Second: verify if the attestation satisfies the "peer device" porfile.
            if (!checkAttestationForPeerDeviceProfile(attestationExtension)) {
                return RESULT_FAILURE;
            }

            // Third: check if the attestation satisfies local binding requirements.
            if (!checkLocalBindingRequirements(
                    leafCertificate, attestationExtension, localBindingType, requirements)) {
                return RESULT_FAILURE;
            }

            return RESULT_SUCCESS;
        } catch (CertificateException | CertPathValidatorException
                | InvalidAlgorithmParameterException | IOException e) {
            // Catch all non-RuntimeExpceptions (all of these are thrown by either getCertificates()
            // or validateCertificateChain() or
            // AndroidKeystoreAttestationVerificationAttributes.fromCertificate())
            debugVerboseLog("Unable to parse/validate Android Attestation certificate(s)", e);
            return RESULT_FAILURE;
        } catch (RuntimeException e) {
            // Catch everyting else (RuntimeExpcetions), since we don't want to throw any exceptions
            // out of this class/method.
            debugVerboseLog("Unexpected error", e);
            return RESULT_FAILURE;
        }
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

        if (requirements.size() != 1) {
            debugVerboseLog("Requirements does not contain exactly 1 key.");
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
            InvalidAlgorithmParameterException  {
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
            for (String certString: getTrustAnchorResources()) {
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
            @NonNull Bundle requirements) {
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

        return true;
    }

    private boolean checkAttestationForPeerDeviceProfile(
            @NonNull AndroidKeystoreAttestationVerificationAttributes attestationAttributes) {
        // Checks for support of Keymaster 4.
        if (attestationAttributes.getAttestationVersion() < 3) {
            debugVerboseLog("Attestation version is not at least 3 (Keymaster 4).");
            return false;
        }

        // Checks for support of Keymaster 4.
        if (attestationAttributes.getKeymasterVersion() < 4) {
            debugVerboseLog("Keymaster version is not at least 4.");
            return false;
        }

        // First two characters are Android OS version.
        if (attestationAttributes.getKeyOsVersion() < 100000) {
            debugVerboseLog("Android OS version is not 10+.");
            return false;
        }

        if (!attestationAttributes.isAttestationHardwareBacked()) {
            debugVerboseLog("Key is not HW backed.");
            return false;
        }

        if (!attestationAttributes.isKeymasterHardwareBacked()) {
            debugVerboseLog("Keymaster is not HW backed.");
            return false;
        }

        if (attestationAttributes.getVerifiedBootState() != VERIFIED) {
            debugVerboseLog("Boot state not Verified.");
            return false;
        }

        try {
            if (!attestationAttributes.isVerifiedBootLocked()) {
                debugVerboseLog("Verified boot state is not locked.");
                return false;
            }
        } catch (IllegalStateException e) {
            debugVerboseLog("VerifiedBootLocked is not set.", e);
            return false;
        }

        // Patch level integer YYYYMM is expected to be within 1 year of today.
        if (!isValidPatchLevel(attestationAttributes.getKeyOsPatchLevel())) {
            debugVerboseLog("OS patch level is not within valid range.");
            return false;
        }

        // Patch level integer YYYYMMDD is expected to be within 1 year of today.
        if (!isValidPatchLevel(attestationAttributes.getKeyBootPatchLevel())) {
            debugVerboseLog("Boot patch level is not within valid range.");
            return false;
        }

        if (!isValidPatchLevel(attestationAttributes.getKeyVendorPatchLevel())) {
            debugVerboseLog("Vendor patch level is not within valid range.");
            return false;
        }

        if (!isValidPatchLevel(attestationAttributes.getKeyBootPatchLevel())) {
            debugVerboseLog("Boot patch level is not within valid range.");
            return false;
        }

        return true;
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
}
