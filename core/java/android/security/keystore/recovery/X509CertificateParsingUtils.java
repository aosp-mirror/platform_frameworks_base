/*
 * Copyright 2018 The Android Open Source Project
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

package android.security.keystore.recovery;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;

/**
 * Static helper methods for decoding {@link X509Certificate} instances.
 *
 * @hide
 */
public class X509CertificateParsingUtils {
    private static final String CERT_FORMAT = "X.509";

    /**
     * Decodes an {@link X509Certificate} encoded as a base-64 string.
     */
    public static X509Certificate decodeBase64Cert(String string) throws CertificateException {
        try {
            return decodeCert(decodeBase64(string));
        } catch (IllegalArgumentException e) {
            throw new CertificateException(e);
        }
    }

    /**
     * Decodes a base-64 string.
     *
     * @throws IllegalArgumentException if not a valid base-64 string.
     */
    private static byte[] decodeBase64(String string) {
        return Base64.getDecoder().decode(string);
    }

    /**
     * Decodes a byte array containing an encoded X509 certificate.
     *
     * @param certBytes the byte array containing the encoded X509 certificate
     * @return the decoded X509 certificate
     * @throws CertificateException if any parsing error occurs
     */
    private static X509Certificate decodeCert(byte[] certBytes) throws CertificateException {
        return decodeCert(new ByteArrayInputStream(certBytes));
    }

    /**
     * Decodes an X509 certificate from an {@code InputStream}.
     *
     * @param inStream the input stream containing the encoded X509 certificate
     * @return the decoded X509 certificate
     * @throws CertificateException if any parsing error occurs
     */
    private static X509Certificate decodeCert(InputStream inStream) throws CertificateException {
        CertificateFactory certFactory;
        try {
            certFactory = CertificateFactory.getInstance(CERT_FORMAT);
        } catch (CertificateException e) {
            // Should not happen, as X.509 is mandatory for all providers.
            throw new RuntimeException(e);
        }
        return (X509Certificate) certFactory.generateCertificate(inStream);
    }
}
