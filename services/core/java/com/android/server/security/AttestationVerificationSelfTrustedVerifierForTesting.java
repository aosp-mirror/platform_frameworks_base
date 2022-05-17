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
import static android.security.attestationverification.AttestationVerificationManager.RESULT_FAILURE;
import static android.security.attestationverification.AttestationVerificationManager.RESULT_SUCCESS;
import static android.security.attestationverification.AttestationVerificationManager.TYPE_CHALLENGE;

import android.annotation.NonNull;
import android.os.Build;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;
import android.util.Slog;

import com.android.internal.org.bouncycastle.asn1.ASN1InputStream;
import com.android.internal.org.bouncycastle.asn1.ASN1ObjectIdentifier;
import com.android.internal.org.bouncycastle.asn1.ASN1OctetString;
import com.android.internal.org.bouncycastle.asn1.ASN1Sequence;
import com.android.internal.org.bouncycastle.asn1.x509.Certificate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Verifies {@code PROFILE_SELF_TRUSTED} attestations.
 *
 * Verifies that the attesting environment can create an attestation with the same root certificate
 * as the verifying device with a matching attestation challenge. Skips CRL revocations checking
 * so this verifier can work in a hermetic test environment.
 *
 * This verifier profile is intended to be used only for testing.
 */
class AttestationVerificationSelfTrustedVerifierForTesting {
    private static final String TAG = "AVF";
    private static final boolean DEBUG = Build.IS_DEBUGGABLE && Log.isLoggable(TAG, Log.VERBOSE);

    // The OID for the extension Android Keymint puts into device-generated certificates.
    private static final String ANDROID_KEYMINT_KEY_DESCRIPTION_EXTENSION_OID =
            "1.3.6.1.4.1.11129.2.1.17";

    // ASN.1 sequence index values for the Android Keymint extension.
    private static final int ATTESTATION_CHALLENGE_INDEX = 4;

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String GOLDEN_ALIAS =
            AttestationVerificationSelfTrustedVerifierForTesting.class.getCanonicalName()
                    + ".Golden";

    private static volatile AttestationVerificationSelfTrustedVerifierForTesting
            sAttestationVerificationSelfTrustedVerifier = null;

    private final CertificateFactory mCertificateFactory;
    private final CertPathValidator mCertPathValidator;
    private final KeyStore mAndroidKeyStore;
    private X509Certificate mGoldenRootCert;

    static AttestationVerificationSelfTrustedVerifierForTesting getInstance()
            throws Exception {
        if (sAttestationVerificationSelfTrustedVerifier == null) {
            synchronized (AttestationVerificationSelfTrustedVerifierForTesting.class) {
                if (sAttestationVerificationSelfTrustedVerifier == null) {
                    sAttestationVerificationSelfTrustedVerifier =
                            new AttestationVerificationSelfTrustedVerifierForTesting();
                }
            }
        }
        return sAttestationVerificationSelfTrustedVerifier;
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

    private AttestationVerificationSelfTrustedVerifierForTesting() throws Exception {
        mCertificateFactory = CertificateFactory.getInstance("X.509");
        mCertPathValidator = CertPathValidator.getInstance("PKIX");
        mAndroidKeyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        mAndroidKeyStore.load(null);
        if (!mAndroidKeyStore.containsAlias(GOLDEN_ALIAS)) {
            KeyPairGenerator kpg =
                    KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE);
            KeyGenParameterSpec parameterSpec = new KeyGenParameterSpec.Builder(
                    GOLDEN_ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAttestationChallenge(GOLDEN_ALIAS.getBytes())
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512).build();
            kpg.initialize(parameterSpec);
            kpg.generateKeyPair();
        }

        X509Certificate[] goldenCerts = (X509Certificate[])
                ((KeyStore.PrivateKeyEntry) mAndroidKeyStore.getEntry(GOLDEN_ALIAS, null))
                        .getCertificateChain();
        mGoldenRootCert = goldenCerts[goldenCerts.length - 1];
    }

    int verifyAttestation(
            int localBindingType, @NonNull Bundle requirements,  @NonNull byte[] attestation) {
        List<X509Certificate> certificates = new ArrayList<>();
        ByteArrayInputStream bis = new ByteArrayInputStream(attestation);
        try {
            while (bis.available() > 0) {
                certificates.add((X509Certificate) mCertificateFactory.generateCertificate(bis));
            }
        } catch (CertificateException e) {
            debugVerboseLog("Unable to parse certificates from attestation", e);
            return RESULT_FAILURE;
        }

        if (localBindingType == TYPE_CHALLENGE
                && validateRequirements(requirements)
                && checkLeafChallenge(requirements, certificates)
                && verifyCertificateChain(certificates)) {
            return RESULT_SUCCESS;
        }

        return RESULT_FAILURE;
    }

    private boolean verifyCertificateChain(List<X509Certificate> certificates) {
        if (certificates.size() < 2) {
            debugVerboseLog("Certificate chain less than 2 in size.");
            return false;
        }

        try {
            CertPath certificatePath = mCertificateFactory.generateCertPath(certificates);
            PKIXParameters validationParams = new PKIXParameters(getTrustAnchors());
            // Skipping revocation checking because we want this to work in a hermetic test
            // environment.
            validationParams.setRevocationEnabled(false);
            mCertPathValidator.validate(certificatePath, validationParams);
        } catch (Throwable t) {
            debugVerboseLog("Invalid certificate chain", t);
            return false;
        }

        return true;
    }

    private Set<TrustAnchor> getTrustAnchors() {
        return Collections.singleton(new TrustAnchor(mGoldenRootCert, null));
    }

    private boolean validateRequirements(Bundle requirements) {
        if (requirements.size() != 1) {
            debugVerboseLog("Requirements does not contain exactly 1 key.");
            return false;
        }
        if (!requirements.containsKey(PARAM_CHALLENGE)) {
            debugVerboseLog("Requirements does not contain key: " + PARAM_CHALLENGE);
            return false;
        }
        return true;
    }

    private boolean checkLeafChallenge(Bundle requirements, List<X509Certificate> certificates) {
        // Verify challenge
        byte[] challenge;
        try {
            challenge = getChallengeFromCert(certificates.get(0));
        } catch (Throwable t) {
            debugVerboseLog("Unable to parse challenge from certificate.", t);
            return false;
        }

        if (Arrays.equals(requirements.getByteArray(PARAM_CHALLENGE), challenge)) {
            return true;
        } else {
            debugVerboseLog("Self-Trusted validation failed; challenge mismatch.");
            return false;
        }
    }

    private byte[] getChallengeFromCert(@NonNull X509Certificate x509Certificate)
            throws CertificateEncodingException, IOException {
        Certificate certificate = Certificate.getInstance(
                new ASN1InputStream(x509Certificate.getEncoded()).readObject());
        ASN1Sequence keyAttributes = (ASN1Sequence) certificate.getTBSCertificate().getExtensions()
                .getExtensionParsedValue(
                        new ASN1ObjectIdentifier(ANDROID_KEYMINT_KEY_DESCRIPTION_EXTENSION_OID));
        return ((ASN1OctetString) keyAttributes.getObjectAt(ATTESTATION_CHALLENGE_INDEX))
                .getOctets();
    }
}
