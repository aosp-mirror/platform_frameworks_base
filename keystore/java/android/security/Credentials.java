/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.security;

import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;

import com.android.internal.org.bouncycastle.util.io.pem.PemObject;
import com.android.internal.org.bouncycastle.util.io.pem.PemReader;
import com.android.internal.org.bouncycastle.util.io.pem.PemWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * {@hide}
 */
public class Credentials {
    private static final String LOGTAG = "Credentials";

    public static final String INSTALL_ACTION = "android.credentials.INSTALL";

    public static final String INSTALL_AS_USER_ACTION = "android.credentials.INSTALL_AS_USER";

    public static final String ACTION_MANAGE_CREDENTIALS = "android.security.MANAGE_CREDENTIALS";

    /**
     * Key prefix for CA certificates.
     *
     * @deprecated Keystore no longer supports unstructured blobs. Public certificates are
     *             stored in typed slots associated with a given alias.
     */
    @Deprecated
    public static final String CA_CERTIFICATE = "CACERT_";

    /**
     * Key prefix for user certificates.
     *
     * @deprecated Keystore no longer supports unstructured blobs. Public certificates are
     *             stored in typed slots associated with a given alias.
     */
    @Deprecated
    public static final String USER_CERTIFICATE = "USRCERT_";

    /**
     * Key prefix for user private and secret keys.
     *
     * @deprecated Keystore no longer uses alias prefixes to discriminate between entry types.
     */
    @Deprecated
    public static final String USER_PRIVATE_KEY = "USRPKEY_";

    /**
     * Key prefix for user secret keys.
     *
     * @deprecated use {@code USER_PRIVATE_KEY} for this category instead.
     */
    @Deprecated
    public static final String USER_SECRET_KEY = "USRSKEY_";

    /** Key prefix for VPN. */
    public static final String VPN = "VPN_";

    /** Key prefix for platform VPNs. */
    public static final String PLATFORM_VPN = "PLATFORM_VPN_";

    /** Key prefix for WIFI. */
    public static final String WIFI = "WIFI_";

    /**
     * Key prefix for App Source certificates.
     *
     * @deprecated This was intended for FS-verity but never used. FS-verity is not
     *             going to use this constant moving forward.
     */
    @Deprecated
    public static final String APP_SOURCE_CERTIFICATE = "FSV_";

    /** Key containing suffix of lockdown VPN profile. */
    public static final String LOCKDOWN_VPN = "LOCKDOWN_VPN";

    /** Name of CA certificate usage. */
    public static final String CERTIFICATE_USAGE_CA = "ca";

    /** Name of User certificate usage. */
    public static final String CERTIFICATE_USAGE_USER = "user";

    /** Name of WIFI certificate usage. */
    public static final String CERTIFICATE_USAGE_WIFI = "wifi";

    /** Name of App Source certificate usage. */
    public static final String CERTIFICATE_USAGE_APP_SOURCE = "appsrc";

    /** Data type for public keys. */
    public static final String EXTRA_PUBLIC_KEY = "KEY";

    /** Data type for private keys. */
    public static final String EXTRA_PRIVATE_KEY = "PKEY";

    // historically used by Android
    public static final String EXTENSION_CRT = ".crt";
    public static final String EXTENSION_P12 = ".p12";
    // commonly used on Windows
    public static final String EXTENSION_CER = ".cer";
    public static final String EXTENSION_PFX = ".pfx";

    /**
     * Intent extra: install the certificate bundle as this UID instead of
     * system.
     */
    public static final String EXTRA_INSTALL_AS_UID = "install_as_uid";

    /**
     * Intent extra: type of the certificate to install
     */
    public static final String EXTRA_CERTIFICATE_USAGE = "certificate_install_usage";

    /**
     * Intent extra: name for the user's key pair.
     */
    public static final String EXTRA_USER_KEY_ALIAS = "user_key_pair_name";

    /**
     * Intent extra: data for the user's private key in PEM-encoded PKCS#8.
     */
    public static final String EXTRA_USER_PRIVATE_KEY_DATA = "user_private_key_data";

    /**
     * Intent extra: data for the user's certificate in PEM-encoded X.509.
     */
    public static final String EXTRA_USER_CERTIFICATE_DATA = "user_certificate_data";

    /**
     * Intent extra: data for CA certificate chain in PEM-encoded X.509.
     */
    public static final String EXTRA_CA_CERTIFICATES_DATA = "ca_certificates_data";

    /**
     * Convert objects to a PEM format which is used for
     * CA_CERTIFICATE and USER_CERTIFICATE entries.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static byte[] convertToPem(Certificate... objects)
            throws IOException, CertificateEncodingException {
        ByteArrayOutputStream bao = new ByteArrayOutputStream();
        Writer writer = new OutputStreamWriter(bao, StandardCharsets.US_ASCII);
        PemWriter pw = new PemWriter(writer);
        for (Certificate o : objects) {
            pw.writeObject(new PemObject("CERTIFICATE", o.getEncoded()));
        }
        pw.close();
        return bao.toByteArray();
    }

    /**
     * Convert objects from PEM format, which is used for
     * CA_CERTIFICATE and USER_CERTIFICATE entries.
     */
    public static List<X509Certificate> convertFromPem(byte[] bytes)
            throws IOException, CertificateException {
        ByteArrayInputStream bai = new ByteArrayInputStream(bytes);
        Reader reader = new InputStreamReader(bai, StandardCharsets.US_ASCII);
        PemReader pr = new PemReader(reader);

        try {
            CertificateFactory cf = CertificateFactory.getInstance("X509");

            List<X509Certificate> result = new ArrayList<X509Certificate>();
            PemObject o;
            while ((o = pr.readPemObject()) != null) {
                if (o.getType().equals("CERTIFICATE")) {
                    Certificate c = cf.generateCertificate(
                            new ByteArrayInputStream(o.getContent()));
                    result.add((X509Certificate) c);
                } else {
                    throw new IllegalArgumentException("Unknown type " + o.getType());
                }
            }
            return result;
        } finally {
            pr.close();
        }
    }
}
