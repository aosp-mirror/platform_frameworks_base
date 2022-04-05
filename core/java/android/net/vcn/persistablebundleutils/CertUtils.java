/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.net.vcn.persistablebundleutils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Objects;

/**
 * CertUtils provides utility methods for constructing Certificate and PrivateKey.
 *
 * @hide
 */
public class CertUtils {
    private static final String CERT_TYPE_X509 = "X.509";
    private static final String PRIVATE_KEY_TYPE_RSA = "RSA";

    /** Decodes an ASN.1 DER encoded Certificate */
    public static X509Certificate certificateFromByteArray(byte[] derEncoded) {
        Objects.requireNonNull(derEncoded, "derEncoded is null");

        try {
            CertificateFactory certFactory = CertificateFactory.getInstance(CERT_TYPE_X509);
            InputStream in = new ByteArrayInputStream(derEncoded);
            return (X509Certificate) certFactory.generateCertificate(in);
        } catch (CertificateException e) {
            throw new IllegalArgumentException("Fail to decode certificate", e);
        }
    }

    /** Decodes a PKCS#8 encoded RSA private key */
    public static RSAPrivateKey privateKeyFromByteArray(byte[] pkcs8Encoded) {
        Objects.requireNonNull(pkcs8Encoded, "pkcs8Encoded was null");
        PKCS8EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(pkcs8Encoded);

        try {
            KeyFactory keyFactory = KeyFactory.getInstance(PRIVATE_KEY_TYPE_RSA);

            return (RSAPrivateKey) keyFactory.generatePrivate(privateKeySpec);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalArgumentException("Fail to decode PrivateKey", e);
        }
    }
}
