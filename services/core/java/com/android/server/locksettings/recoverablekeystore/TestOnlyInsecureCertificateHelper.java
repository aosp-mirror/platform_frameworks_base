/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.locksettings.recoverablekeystore;

import static android.security.keystore.recovery.RecoveryController.ERROR_INVALID_CERTIFICATE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.security.keystore.recovery.TrustedRootCertificates;
import android.util.Log;
import android.util.Pair;

import com.android.internal.widget.LockPatternUtils;

import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.SecretKey;

/**
 * The class provides helper methods to support end-to-end test with insecure certificate.
 */
public class TestOnlyInsecureCertificateHelper {
    private static final String TAG = "TestCertHelper";

    /**
     * Constructor for the helper class.
     */
    public TestOnlyInsecureCertificateHelper() {
    }

    /**
     * Returns a root certificate installed in the system for given alias.
     * Returns default secure certificate if alias is empty or null.
     * Can return insecure certificate for its alias.
     */
    public @NonNull X509Certificate
            getRootCertificate(String rootCertificateAlias) throws RemoteException {
        rootCertificateAlias = getDefaultCertificateAliasIfEmpty(rootCertificateAlias);
        if (isTestOnlyCertificateAlias(rootCertificateAlias)) {
            return TrustedRootCertificates.getTestOnlyInsecureCertificate();
        }

        X509Certificate rootCertificate =
                TrustedRootCertificates.getRootCertificate(rootCertificateAlias);
        if (rootCertificate == null) {
            throw new ServiceSpecificException(
                    ERROR_INVALID_CERTIFICATE, "The provided root certificate alias is invalid");
        }
        return rootCertificate;
    }

    public @NonNull String getDefaultCertificateAliasIfEmpty(
            @Nullable String rootCertificateAlias) {
        if (rootCertificateAlias == null || rootCertificateAlias.isEmpty()) {
            Log.e(TAG, "rootCertificateAlias is null or empty - use secure default value");
            // Use the default Google Key Vault Service CA certificate if the alias is not provided
            rootCertificateAlias = TrustedRootCertificates.GOOGLE_CLOUD_KEY_VAULT_SERVICE_V1_ALIAS;
        }
        return rootCertificateAlias;
    }

    public boolean isTestOnlyCertificateAlias(String rootCertificateAlias) {
        return TrustedRootCertificates.TEST_ONLY_INSECURE_CERTIFICATE_ALIAS
                .equals(rootCertificateAlias);
    }

    public boolean isValidRootCertificateAlias(String rootCertificateAlias) {
        return TrustedRootCertificates.getRootCertificates().containsKey(rootCertificateAlias)
                || isTestOnlyCertificateAlias(rootCertificateAlias);
    }

    public boolean doesCredentialSupportInsecureMode(int credentialType, String credential) {
        return (credentialType == LockPatternUtils.CREDENTIAL_TYPE_PASSWORD)
            && (credential != null)
            && credential.startsWith(TrustedRootCertificates.INSECURE_PASSWORD_PREFIX);
    }

    public Map<String, Pair<SecretKey, byte[]>> keepOnlyWhitelistedInsecureKeys(
            Map<String, Pair<SecretKey, byte[]>> rawKeys) {
        if (rawKeys == null) {
            return null;
        }
        Map<String, Pair<SecretKey, byte[]>> filteredKeys = new HashMap<>();
        for (Map.Entry<String, Pair<SecretKey, byte[]>> entry : rawKeys.entrySet()) {
            String alias = entry.getKey();
            if (alias != null
                    && alias.startsWith(TrustedRootCertificates.INSECURE_KEY_ALIAS_PREFIX)) {
                filteredKeys.put(entry.getKey(),
                        Pair.create(entry.getValue().first, entry.getValue().second));
                Log.d(TAG, "adding key with insecure alias " + alias + " to the recovery snapshot");
            }
        }
        return filteredKeys;
    }
}
