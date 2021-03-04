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
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Objects;

/**
 * CertUtils provides utility methods for constructing Certificate.
 *
 * @hide
 */
public class CertUtils {
    private static final String CERT_TYPE_X509 = "X.509";

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
}
